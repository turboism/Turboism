package dev.turboism.validation.selectionlag;

import dev.turboism.sdk.cubism.SelectionSnapshot;

import java.awt.AWTEvent;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.awt.event.MouseEvent;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Offline unit tests for selection-lag probe behavior and regressions. */
public final class SelectionLagProbePluginTest {

    public static void main(final String[] args) throws Exception {
        testTitleBarActivationPoints();
        testAmortizedSamplesCapacityExpansion();
        testSinglePendingHeartbeatAndBlockedStackCapture();
        testCrossPhaseHeartbeatLatencyAttribution();
        testWorkloadPassRequiresCompleteDragGesture();
        testSelectionSupplierContractsAndSetComparison();
        testStopFreezesDataAgainstLateCallbacksAndEvents();
        testBoundedHostThreadWaitAndLateCallbackSafety();
        testSwitchPhaseEdtBarrierAndGestureReset();
        System.out.println("PASS: SelectionLagProbePluginTest");
    }

    private static void testTitleBarActivationPoints() {
        final Point bounds50 = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(-4, -30, 950, 1046));
        assertEquals(471, bounds50.x, "5.0.x x coordinate");
        assertEquals(20, bounds50.y, "5.0.x y coordinate clamped to visible client area");

        final Point bounds53 = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(-4, -10, 950, 1046));
        assertEquals(471, bounds53.x, "5.2/5.3 x coordinate");
        assertEquals(20, bounds53.y, "5.2/5.3 y coordinate clamped to visible client area");

        final Point floating = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(100, 50, 800, 600));
        assertEquals(500, floating.x, "floating window x coordinate");
        assertEquals(80, floating.y, "floating window y coordinate");
    }

    private static void testAmortizedSamplesCapacityExpansion() {
        final SelectionLagProbePlugin.AmortizedSamples samples = new SelectionLagProbePlugin.AmortizedSamples();
        for (int i = 0; i < 1_000; i++) {
            samples.add(i * 10L);
        }
        assertEquals(1_000, samples.size(), "sample count");
        final long[] arr = samples.toArray();
        assertEquals(1_000, arr.length, "array length");
        assertEquals(0L, arr[0], "first element");
        assertEquals(9_990L, arr[999], "last element");
    }

    private static void testSinglePendingHeartbeatAndBlockedStackCapture() {
        // Start clock at negative value to prove robustness against negative nanoTime origins
        final AtomicLong clockNanos = new AtomicLong(-50_000_000_000L);
        final List<Runnable> dispatched = new ArrayList<>();
        final SelectionLagProbePlugin.EdtSampler sampler = new SelectionLagProbePlugin.EdtSampler(
            Thread.currentThread().getId(),
            100L, // 100 ms threshold
            clockNanos::get,
            dispatched::add
        );

        // 1. Tick 1: enqueues single heartbeat
        sampler.startObservation();
        sampler.heartbeatTick();
        assertEquals(1, dispatched.size(), "tick 1 must enqueue exactly 1 heartbeat");

        // 2. Tick 2 without running callback: must NOT enqueue second heartbeat
        clockNanos.addAndGet(25_000_000L); // +25 ms
        sampler.heartbeatTick();
        assertEquals(1, dispatched.size(), "tick 2 while pending must not enqueue duplicate heartbeat");

        // 3. Advance clock past 100 ms threshold while callback is still unexecuted:
        clockNanos.addAndGet(100_000_000L); // +100 ms (total 125 ms blocked)
        sampler.heartbeatTick();
        assertEquals(1, dispatched.size(), "queue size remains 1 during blocked EDT");

        // 4. Run single watchdog tick manually: captures blocked stack on current thread
        sampler.watchdogTick();

        // 5. Verify real stack capture in exportStats
        final SelectionLagProbePlugin.ProbeResult result = new SelectionLagProbePlugin.ProbeResult();
        sampler.exportStats(result);
        assertEquals(3, result.stackCaptures.size(), "each trigger captures CAPTURE_SHOTS (3) stack shots");
        assertEquals("HEARTBEAT_BLOCKED", result.stackCaptures.get(0).trigger(),
            "trigger reason must be HEARTBEAT_BLOCKED");
        assertTrue(result.stackCaptures.get(0).triggerMillis() >= 100L,
            "triggerMillis must be >= threshold");

        // 6. Dispatcher executes the pending callback:
        clockNanos.addAndGet(10_000_000L); // +10 ms
        final Runnable callback = dispatched.remove(0);
        callback.run();

        sampler.stop();
    }

    private static void testCrossPhaseHeartbeatLatencyAttribution() {
        final AtomicLong clockNanos = new AtomicLong(0L);
        final List<Runnable> dispatched = new ArrayList<>();
        final SelectionLagProbePlugin.EdtSampler sampler = new SelectionLagProbePlugin.EdtSampler(
            Thread.currentThread().getId(),
            100L,
            clockNanos::get,
            dispatched::add
        );

        sampler.startObservation();
        sampler.setPhase(SelectionLagProbePlugin.Phase.ACTIVATION);

        // Enqueue heartbeat while in ACTIVATION phase
        sampler.heartbeatTick();
        assertEquals(1, dispatched.size(), "heartbeat enqueued");

        // Phase switches to INTERACTION before callback executes on EDT
        sampler.setPhase(SelectionLagProbePlugin.Phase.INTERACTION);
        clockNanos.addAndGet(40_000_000L); // +40 ms latency

        // Callback executes on simulated EDT
        final Runnable callback = dispatched.remove(0);
        callback.run();

        // Verify latency sample is attributed to ACTIVATION (the enqueue phase), not INTERACTION
        final SelectionLagProbePlugin.ProbeResult result = new SelectionLagProbePlugin.ProbeResult();
        sampler.exportStats(result);

        assertEquals(1L, result.phases.get("ACTIVATION").heartbeatCount(),
            "latency sample must be recorded to the phase when heartbeat was enqueued (ACTIVATION)");
        assertEquals(0L, result.phases.get("INTERACTION").heartbeatCount(),
            "INTERACTION phase must receive no latency sample from prior phase's heartbeat");

        sampler.stop();
    }

    private static void testWorkloadPassRequiresCompleteDragGesture() {
        final AtomicLong clockNanos = new AtomicLong(10_000_000L);
        final SelectionLagProbePlugin.EdtSampler sampler = new SelectionLagProbePlugin.EdtSampler(
            Thread.currentThread().getId(),
            100L,
            clockNanos::get,
            runnable -> {}
        );

        sampler.startObservation();

        // 1. Activation phase events must not count towards interaction
        sampler.setPhase(SelectionLagProbePlugin.Phase.ACTIVATION);
        sampler.onMouseEvent(MouseEvent.MOUSE_PRESSED);
        sampler.onMouseEvent(MouseEvent.MOUSE_DRAGGED);
        sampler.onMouseEvent(MouseEvent.MOUSE_RELEASED);

        assertEquals(3, sampler.activationMouseEventCount(), "activation mouse events");
        assertEquals(0, sampler.interactionMouseEventCount(), "interaction mouse events must be 0 during activation");

        // 2. Switch to INTERACTION: only moves and clicks do NOT constitute complete drag gestures
        sampler.setPhase(SelectionLagProbePlugin.Phase.INTERACTION);
        sampler.resetEventGap();

        sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);
        sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);
        sampler.onMouseEvent(MouseEvent.MOUSE_CLICKED);

        assertEquals(3, sampler.interactionMouseEventCount(), "interaction event count");
        assertEquals(0, sampler.interactionDragCount(), "no drag events from move/click");
        assertEquals(0, sampler.interactionCompletedGestures(), "no completed gesture from move/click");

        // 3. Complete drag gesture: MOUSE_PRESSED -> MOUSE_DRAGGED -> MOUSE_RELEASED
        sampler.onMouseEvent(MouseEvent.MOUSE_PRESSED);
        sampler.onMouseEvent(MouseEvent.MOUSE_DRAGGED);
        sampler.onMouseEvent(MouseEvent.MOUSE_RELEASED);

        assertEquals(6, sampler.interactionMouseEventCount(), "interaction event count after drag");
        assertEquals(1, sampler.interactionDragCount(), "1 drag event recorded");
        assertEquals(1, sampler.interactionCompletedGestures(), "1 complete drag gesture recorded");

        sampler.stop();
    }

    private static void testSelectionSupplierContractsAndSetComparison() {
        final SelectionLagProbePlugin plugin = new SelectionLagProbePlugin(
            System::nanoTime,
            Runnable::run
        );

        // 1. Successful non-empty snapshot
        final SelectionSnapshot snapshot1 = new SelectionSnapshot(
            List.of("idA"), Optional.empty(), Optional.of("meshA"), Optional.empty());
        final SelectionLagProbePlugin.SelectionEvidence successPopulated =
            plugin.readSelectionBestEffort(() -> snapshot1, 1000L);
        assertEquals(SelectionLagProbePlugin.SelectionReadStatus.SUCCESS, successPopulated.readStatus(),
            "valid snapshot must return SUCCESS status");
        assertEquals(List.of("idA"), successPopulated.selectedIds(), "selected IDs populated");
        assertEquals("meshA", successPopulated.activeArtMesh(), "art mesh populated");

        // 2. Successful empty snapshot
        final SelectionSnapshot emptySnapshot = new SelectionSnapshot(
            List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        final SelectionLagProbePlugin.SelectionEvidence successEmpty =
            plugin.readSelectionBestEffort(() -> emptySnapshot, 1000L);
        assertEquals(SelectionLagProbePlugin.SelectionReadStatus.SUCCESS, successEmpty.readStatus(),
            "valid empty snapshot must return SUCCESS status");
        assertTrue(successEmpty.selectedIds().isEmpty(), "empty IDs list");

        // 3. Null return from reader fails closed as FAILED
        final SelectionLagProbePlugin.SelectionEvidence nullEvidence =
            plugin.readSelectionBestEffort(() -> null, 1000L);
        assertEquals(SelectionLagProbePlugin.SelectionReadStatus.FAILED, nullEvidence.readStatus(),
            "null snapshot must fail closed as FAILED");

        // 4. RuntimeException thrown from reader fails closed as FAILED
        final SelectionLagProbePlugin.SelectionEvidence failedEvidence =
            plugin.readSelectionBestEffort(() -> {
                throw new RuntimeException("cubism selection read failure");
            }, 1000L);
        assertEquals(SelectionLagProbePlugin.SelectionReadStatus.FAILED, failedEvidence.readStatus(),
            "exception from selection supplier must yield FAILED status");

        // 5. Timeout simulation returns TIMEOUT status
        final SelectionLagProbePlugin timeoutPlugin = new SelectionLagProbePlugin(
            System::nanoTime,
            runnable -> {} // drops execution to trigger timeout
        );
        final SelectionLagProbePlugin.SelectionEvidence timeoutEvidence =
            timeoutPlugin.readSelectionBestEffort(() -> emptySnapshot, 50L);
        assertEquals(SelectionLagProbePlugin.SelectionReadStatus.TIMEOUT, timeoutEvidence.readStatus(),
            "blocked host thread must yield TIMEOUT status");

        // 6. Selection change comparison tests:
        final SelectionLagProbePlugin.SelectionEvidence successItems =
            new SelectionLagProbePlugin.SelectionEvidence(List.of("idA", "idB"), "artmesh1", SelectionLagProbePlugin.SelectionReadStatus.SUCCESS, 5);
        final SelectionLagProbePlugin.SelectionEvidence successReordered =
            new SelectionLagProbePlugin.SelectionEvidence(List.of("idB", "idA"), "artmesh1", SelectionLagProbePlugin.SelectionReadStatus.SUCCESS, 5);

        // Comparing success with failed/timeout must NOT register as selection change:
        assertFalse(SelectionLagProbePlugin.computeSelectionChanged(successEmpty, failedEvidence),
            "comparison with FAILED read must not register selection change");
        assertFalse(SelectionLagProbePlugin.computeSelectionChanged(successEmpty, timeoutEvidence),
            "comparison with TIMEOUT read must not register selection change");

        // Reordered ID list with same items must NOT register as selection change (Set comparison):
        assertFalse(SelectionLagProbePlugin.computeSelectionChanged(successItems, successReordered),
            "reordered selection IDs must not be reported as selection change");

        // Real selection change:
        assertTrue(SelectionLagProbePlugin.computeSelectionChanged(successEmpty, successItems),
            "transition from empty to populated selection must be detected as change");
    }

    private static void testStopFreezesDataAgainstLateCallbacksAndEvents() throws Exception {
        final AtomicLong clockNanos = new AtomicLong(100_000L);
        final List<Runnable> dispatched = new ArrayList<>();
        final CountDownLatch threadInfoEntered = new CountDownLatch(1);
        final CountDownLatch threadInfoBlock = new CountDownLatch(1);
        final AtomicBoolean intercept = new AtomicBoolean(false);
        final AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread captureWorker = null;

        // Real non-null ThreadInfo with dev.turboism.* stack frames
        final ThreadInfo realThreadInfo = ManagementFactory.getThreadMXBean()
            .getThreadInfo(Thread.currentThread().getId(), 64);
        if (realThreadInfo == null) {
            throw new AssertionError("ManagementFactory must provide ThreadInfo for current thread");
        }

        // Custom ThreadMXBean proxy to intercept getThreadInfo during stop()
        final ThreadMXBean bean = (ThreadMXBean) Proxy.newProxyInstance(
            SelectionLagProbePluginTest.class.getClassLoader(),
            new Class<?>[] {ThreadMXBean.class},
            (proxy, method, args) -> {
                if ("getThreadInfo".equals(method.getName())) {
                    if (intercept.get()) {
                        threadInfoEntered.countDown();
                        try {
                            threadInfoBlock.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new ThreadInfo[] {realThreadInfo};
                }
                return null;
            }
        );

        final SelectionLagProbePlugin.EdtSampler sampler = new SelectionLagProbePlugin.EdtSampler(
            Thread.currentThread().getId(),
            100L,
            clockNanos::get,
            dispatched::add,
            bean
        );

        final List<AWTEventListener> beforeStart = getActualAWTEventListeners();
        try {
            // 1. Verify AWT listener registration and unwrapping of proxies
            sampler.startObservation();
            final List<AWTEventListener> afterStart = getActualAWTEventListeners();
            assertEquals(beforeStart.size() + 1, afterStart.size(), "exactly one AWT listener must be registered");
            final AWTEventListener registeredListener = afterStart.stream()
                .filter(l -> !beforeStart.contains(l))
                .findFirst()
                .orElseThrow();

            // 2. Verify positive capture works during normal running observation
            sampler.setPhase(SelectionLagProbePlugin.Phase.BASELINE);
            sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);
            clockNanos.addAndGet(200_000_000L); // +200 ms triggers event gap
            sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);
            sampler.watchdogTick();

            final SelectionLagProbePlugin.ProbeResult baselineResult = new SelectionLagProbePlugin.ProbeResult();
            sampler.exportStats(baselineResult);
            assertEquals(3, baselineResult.stackCaptures.size(), "baseline positive capture must commit 3 shots");
            assertTrue(baselineResult.turboismFrameCounts.size() > 0, "turboism frames must be counted");

            // 3. Now test late commit block during stop()
            intercept.set(true);
            sampler.setPhase(SelectionLagProbePlugin.Phase.INTERACTION);
            sampler.resetEventGap();
            sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);
            clockNanos.addAndGet(400_000_000L); // +400 ms exceeds 300 ms cooldown and 100 ms threshold
            sampler.onMouseEvent(MouseEvent.MOUSE_MOVED);

            // Queue a heartbeat in BASELINE before stop
            sampler.setPhase(SelectionLagProbePlugin.Phase.BASELINE);
            sampler.heartbeatTick();
            assertEquals(1, dispatched.size(), "1 heartbeat queued before stop");

            // Run watchdogTick on background thread; it will enter getThreadInfo and block
            captureWorker = new Thread(() -> {
                try {
                    sampler.watchdogTick();
                } catch (Throwable t) {
                    workerError.set(t);
                }
            }, "test-capture-worker");
            captureWorker.start();

            assertTrue(threadInfoEntered.await(2, TimeUnit.SECONDS), "watchdog must enter getThreadInfo");

            // 4. Invoke stop() while capture is blocked in getThreadInfo
            sampler.stop();

            // Verify AWT listener is unregistered from Toolkit and original listeners are retained
            final List<AWTEventListener> afterStop = getActualAWTEventListeners();
            assertFalse(afterStop.contains(registeredListener), "registered listener must be removed after stop");
            assertEquals(beforeStart.size(), afterStop.size(), "listeners count restored to initial set");
            assertTrue(afterStop.containsAll(beforeStart), "original listeners must be fully retained after stop");

            // Export stats immediately after stop
            final SelectionLagProbePlugin.ProbeResult result1 = new SelectionLagProbePlugin.ProbeResult();
            sampler.exportStats(result1);
            assertEquals(3, result1.stackCaptures.size(), "no new captures committed before unblocking");

            // Unblock getThreadInfo: late commit attempt must see !running and be dropped
            threadInfoBlock.countDown();
            captureWorker.join(2_000L);
            assertFalse(captureWorker.isAlive(), "captureWorker must terminate cleanly");
            if (workerError.get() != null) {
                throw new AssertionError("captureWorker threw exception", workerError.get());
            }

            // Run late pre-queued heartbeat callback (enqueued in BASELINE): must see !running and drop sample
            final Runnable lateHeartbeat = dispatched.remove(0);
            lateHeartbeat.run();

            // Inject late mouse events in multiple phases after stop: must be dropped
            sampler.setPhase(SelectionLagProbePlugin.Phase.BASELINE);
            sampler.onMouseEvent(MouseEvent.MOUSE_PRESSED);
            sampler.setPhase(SelectionLagProbePlugin.Phase.INTERACTION);
            sampler.onMouseEvent(MouseEvent.MOUSE_PRESSED);

            // Re-export and verify exact freeze across all counters, captures, and phase stats
            final SelectionLagProbePlugin.ProbeResult result2 = new SelectionLagProbePlugin.ProbeResult();
            sampler.exportStats(result2);

            assertEquals(result1.stackCaptures, result2.stackCaptures, "stackCaptures list frozen");
            assertEquals(result1.turboismFrameCounts, result2.turboismFrameCounts, "turboismFrameCounts frozen");
            assertEquals(result1.activationMouseEvents, result2.activationMouseEvents, "activation events frozen");
            assertEquals(result1.interactionMouseEvents, result2.interactionMouseEvents, "interaction events frozen");
            assertEquals(result1.interactionCompletedGestures, result2.interactionCompletedGestures, "gestures frozen");
            assertEquals(result1.interactionDragCount, result2.interactionDragCount, "drag count frozen");
            assertEquals(result1.overThresholdCount, result2.overThresholdCount, "overThresholdCount frozen");

            for (SelectionLagProbePlugin.Phase p : SelectionLagProbePlugin.Phase.values()) {
                final SelectionLagProbePlugin.PhaseStats s1 = result1.phases.get(p.name());
                final SelectionLagProbePlugin.PhaseStats s2 = result2.phases.get(p.name());
                assertNotNull(s1, "phase stats present in result1: " + p.name());
                assertNotNull(s2, "phase stats present in result2: " + p.name());
                assertEquals(s1.eventCount(), s2.eventCount(), "eventCount frozen for " + p.name());
                assertEquals(s1.heartbeatCount(), s2.heartbeatCount(), "heartbeatCount frozen for " + p.name());
                assertTrue(Arrays.equals(s1.eventGapMillis(), s2.eventGapMillis()), "eventGapMillis percentiles frozen for " + p.name());
                assertTrue(Arrays.equals(s1.heartbeatLatencyMillis(), s2.heartbeatLatencyMillis()), "heartbeatLatencyMillis percentiles frozen for " + p.name());
            }
        } finally {
            sampler.stop();
            threadInfoBlock.countDown();
            if (captureWorker != null) {
                captureWorker.interrupt();
                captureWorker.join(1_000L);
                assertFalse(captureWorker.isAlive(), "captureWorker must be dead in finally");
            }
        }
    }

    private static List<AWTEventListener> getActualAWTEventListeners() {
        final List<AWTEventListener> list = new ArrayList<>();
        for (AWTEventListener l : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
            if (l instanceof AWTEventListenerProxy proxy) {
                list.add(proxy.getListener());
            } else {
                list.add(l);
            }
        }
        return list;
    }

    private static void testBoundedHostThreadWaitAndLateCallbackSafety() throws Exception {
        // 1. Threaded concurrency test: verify host thread is not interrupted by cancel(false)
        final BlockingQueue<Runnable> hostQueue = new LinkedBlockingQueue<>();
        final CountDownLatch callableStarted = new CountDownLatch(1);
        final CountDownLatch callableBlock = new CountDownLatch(1);
        final AtomicBoolean hostInterrupted = new AtomicBoolean(false);
        final AtomicBoolean callableFinished = new AtomicBoolean(false);
        final AtomicReference<Throwable> hostThreadError = new AtomicReference<>();

        final SelectionLagProbePlugin plugin = new SelectionLagProbePlugin(
            System::nanoTime,
            task -> {
                hostQueue.add(task);
                try {
                    if (!callableStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("callable failed to start on host thread within 2s");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for callable to start", e);
                }
            }
        );

        final Thread hostThread = new Thread(() -> {
            try {
                final Runnable task = hostQueue.take();
                task.run();
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    hostInterrupted.set(true);
                } else {
                    hostThreadError.set(e);
                }
            }
        }, "simulated-host-edt-thread");
        hostThread.start();

        try {
            boolean timeoutOccurred = false;
            try {
                plugin.onHostThread(() -> {
                    callableStarted.countDown();
                    try {
                        callableBlock.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        hostInterrupted.set(true);
                    }
                    callableFinished.set(true);
                    return "result";
                }, 50L);
            } catch (TimeoutException expected) {
                timeoutOccurred = true;
            }
            assertTrue(timeoutOccurred, "onHostThread must time out within bounded wait (50 ms)");

            // Verify future.cancel(false) did NOT interrupt the host thread
            assertFalse(hostInterrupted.get(), "host thread must NOT be interrupted by cancel(false)");
            assertFalse(hostThread.isInterrupted(), "host thread interrupt flag must be false");

            // Unblock callable and wait for clean finish
            callableBlock.countDown();
            hostThread.join(2_000L);
            assertFalse(hostThread.isAlive(), "hostThread must terminate cleanly after join");
            assertTrue(callableFinished.get(), "callable completes cleanly without error");
            if (hostThreadError.get() != null) {
                throw new AssertionError("hostThread encountered error", hostThreadError.get());
            }
        } finally {
            callableBlock.countDown();
            hostThread.interrupt();
            hostThread.join(1_000L);
            assertFalse(hostThread.isAlive(), "hostThread must be dead in finally");
        }

        // 2. Unstarted future cancelled after timeout: manual execution must not run callable
        final List<Runnable> unstartedQueue = new ArrayList<>();
        final SelectionLagProbePlugin unstartedPlugin = new SelectionLagProbePlugin(
            System::nanoTime,
            unstartedQueue::add
        );
        final AtomicBoolean unstartedExecuted = new AtomicBoolean(false);
        boolean unstartedTimedOut = false;
        try {
            unstartedPlugin.onHostThread(() -> {
                unstartedExecuted.set(true);
                return "ok";
            }, 50L);
        } catch (TimeoutException expected) {
            unstartedTimedOut = true;
        }
        assertTrue(unstartedTimedOut, "unstarted task must time out");
        assertEquals(1, unstartedQueue.size(), "task was enqueued");
        unstartedQueue.get(0).run(); // execute on simulated EDT after cancel(false)
        assertFalse(unstartedExecuted.get(), "cancelled FutureTask must never execute callable if cancelled before start");
    }

    private static void testSwitchPhaseEdtBarrierAndGestureReset() {
        final SelectionLagProbePlugin plugin = new SelectionLagProbePlugin(
            System::nanoTime,
            runnable -> {} // stalls dispatcher to trigger timeout
        );
        final SelectionLagProbePlugin.EdtSampler sampler = new SelectionLagProbePlugin.EdtSampler(
            Thread.currentThread().getId(),
            100L,
            System::nanoTime,
            runnable -> {}
        );
        sampler.startObservation();
        sampler.setPhase(SelectionLagProbePlugin.Phase.ACTIVATION);

        // 1. Barrier timeout: switchPhase must propagate TimeoutException, phase unchanged
        boolean barrierFailed = false;
        try {
            plugin.switchPhase(sampler, SelectionLagProbePlugin.Phase.INTERACTION);
        } catch (TimeoutException expected) {
            barrierFailed = true;
        } catch (Exception unexpected) {
            throw new AssertionError("unexpected exception: " + unexpected);
        }
        assertTrue(barrierFailed, "switchPhase must propagate failure if EDT barrier times out");
        assertEquals(SelectionLagProbePlugin.Phase.ACTIVATION, sampler.getPhase(),
            "phase must remain unchanged if barrier fails");

        // 2. Successful switchPhase resets gesture state so residual release does not count
        final SelectionLagProbePlugin directPlugin = new SelectionLagProbePlugin(
            System::nanoTime,
            Runnable::run
        );
        // Start in INTERACTION and generate press + drag
        sampler.setPhase(SelectionLagProbePlugin.Phase.INTERACTION);
        sampler.onMouseEvent(MouseEvent.MOUSE_PRESSED);
        sampler.onMouseEvent(MouseEvent.MOUSE_DRAGGED);

        // Switch INTERACTION -> ACTIVATION -> INTERACTION via switchPhase: gesture state must be reset
        try {
            directPlugin.switchPhase(sampler, SelectionLagProbePlugin.Phase.ACTIVATION);
            directPlugin.switchPhase(sampler, SelectionLagProbePlugin.Phase.INTERACTION);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Release arriving in INTERACTION phase must NOT complete a gesture
        sampler.onMouseEvent(MouseEvent.MOUSE_RELEASED);
        final SelectionLagProbePlugin.ProbeResult result = new SelectionLagProbePlugin.ProbeResult();
        sampler.exportStats(result);

        assertEquals(0, result.interactionCompletedGestures,
            "release after returning to INTERACTION must not complete gesture from prior press/drag");

        sampler.stop();
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - expected: " + expected + ", but was: " + actual);
        }
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean condition, final String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(final Object obj, final String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }
}
