package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSchedulerTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void lightweightTaskIsDispatchedToPluginExecutorAndRunsAsynchronously() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        // When
        scheduler.dispatch(task("action.handle", "none"), () -> {
            workerThread.set(Thread.currentThread().getName());
            completed.countDown();
        });

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void rejectedTaskIsNotExecutedAndEmitsDiagnosticEvent() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        AtomicInteger executions = new AtomicInteger();

        // When
        scheduler.dispatch(task("network", "none"), executions::incrementAndGet);

        // Then
        assertEquals(0, executions.get());
        assertEquals(List.of(new PluginWorkBudgetEvent(
            PLUGIN_ID,
            "network",
            PluginWorkBudgetEvent.Phase.REJECTED,
            PluginWorkBudgetEvent.Decision.REJECTED,
            PluginWorkBudgetEvent.Severity.WARNING
        )), events);
        scheduler.shutdown();
    }

    @Test
    void sidecarTaskIsHandedToSidecarDispatcher() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        RuntimeScheduler scheduler = scheduler(events, sidecar);
        PluginTask task = task("ai", "sidecar");

        // When
        scheduler.dispatch(task, () -> { });

        // Then
        assertSame(task, sidecar.task.get());
        assertNotNull(sidecar.callback.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void heavyTaskUsesAvailableSidecarAndNeverEntersPluginExecutor() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        final RuntimeScheduler scheduler = scheduler(events, sidecar);
        final AtomicInteger executions = new AtomicInteger();
        final PluginTask heavy = task("transaction.commit", "none");

        scheduler.dispatch(heavy, executions::incrementAndGet);

        assertSame(heavy, sidecar.task.get());
        assertEquals(0, executions.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void heavyTaskIsPolicyRejectedWhenSidecarIsUnavailable() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(events, SidecarDispatcher.noop());
        final AtomicInteger executions = new AtomicInteger();

        scheduler.dispatch(task("transaction.commit", "none"), executions::incrementAndGet);

        assertEquals(0, executions.get());
        assertEquals(PluginWorkBudgetEvent.Phase.REJECTED, events.get(0).phase());
        scheduler.shutdown();
    }

    @Test
    void globalTimerLimitRejectsOverflowWithoutBlockingAndReleasesPermitsExactlyOnce() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        final java.util.ArrayList<RuntimeTimerSubmission> timers = new java.util.ArrayList<>();
        for (int index = 0; index < 1024; index++) {
            final RuntimeTimerSubmission submission = scheduler.schedule(Duration.ofHours(1), () -> { });
            assertTrue(submission.accepted(), "timer " + index + " should be accepted");
            timers.add(submission);
        }

        final long startedAt = System.nanoTime();
        assertFalse(scheduler.schedule(Duration.ofHours(1), () -> { }).accepted());
        assertTrue(
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 100,
            "timer admission must not block"
        );
        assertTrue(timers.get(0).handle().cancel());
        assertFalse(timers.get(0).handle().cancel());
        assertTrue(scheduler.schedule(Duration.ofHours(1), () -> { }).accepted());
        scheduler.shutdown();
    }

    @Test
    void executedTimerReleasesPermitExactlyOnce() throws Exception {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        final CountDownLatch executed = new CountDownLatch(1);
        assertTrue(scheduler.schedule(Duration.ZERO, executed::countDown).accepted());
        assertTrue(executed.await(1, TimeUnit.SECONDS));
        for (int index = 0; index < 1024; index++) {
            assertTrue(scheduler.schedule(Duration.ofHours(1), () -> { }).accepted());
        }
        assertFalse(scheduler.schedule(Duration.ofHours(1), () -> { }).accepted());
        scheduler.shutdown();
    }

    @Test
    void shutdownReleasesEveryActiveTimerPermitWithoutUnderflow() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        final java.util.ArrayList<RuntimeTimerSubmission> timers = new java.util.ArrayList<>();
        for (int index = 0; index < 1024; index++) {
            final RuntimeTimerSubmission submission = scheduler.schedule(Duration.ofHours(1), () -> { });
            assertTrue(submission.accepted());
            timers.add(submission);
        }
        assertEquals(1024, scheduler.activeTimerCount());
        assertEquals(0, scheduler.availableTimerPermits());

        scheduler.shutdown();

        assertEquals(0, scheduler.activeTimerCount());
        assertEquals(1024, scheduler.availableTimerPermits());
        assertFalse(timers.get(0).handle().cancel());
        assertEquals(1024, scheduler.availableTimerPermits());
    }

    @Test
    void executedCancelAndShutdownRaceDoesNotUnderflowTimerPermits() throws Exception {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RuntimeTimerSubmission timer = scheduler.schedule(Duration.ZERO, () -> {
            started.countDown();
            await(release);
        });
        assertTrue(timer.accepted());
        assertTrue(started.await(1, TimeUnit.SECONDS));

        final Thread shutdown = new Thread(scheduler::shutdown, "timer-shutdown-race");
        shutdown.start();
        timer.handle().cancel();
        release.countDown();
        shutdown.join(1_000);

        assertFalse(shutdown.isAlive());
        assertEquals(0, scheduler.activeTimerCount());
        assertEquals(1024, scheduler.availableTimerPermits());
        assertFalse(timer.handle().cancel());
    }

    @Test
    void shutdownDrainsAllPluginExecutors() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        scheduler.dispatch(task("event.subscribe", "none"), () -> {
            firstStarted.countDown();
            await(releaseFirst);
            completed.countDown();
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        scheduler.dispatch(new PluginTask("action.handle", "dev.turboism.plugin.other", "payload", "none"), completed::countDown);

        // When
        releaseFirst.countDown();
        scheduler.shutdown();

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(events.isEmpty());
    }

    @Test
    void cancellationContextIsClearedAfterCallbackFinishes() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        RuntimeScheduler scheduler = scheduler(events, sidecar);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<RuntimeCancellationToken> tokenDuringCallback = new AtomicReference<>();

        // When
        scheduler.dispatch(task("ai", "sidecar"), () -> {
            tokenDuringCallback.set(CancellationContext.get());
            completed.countDown();
        });
        sidecar.callback.get().run();

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotNull(tokenDuringCallback.get());
        assertNull(CancellationContext.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void sidecarCompletionCallbackRunsThroughPluginExecutor() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        RuntimeScheduler scheduler = scheduler(events, sidecar);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        String sidecarThread = Thread.currentThread().getName();

        // When
        scheduler.dispatch(task("ai", "sidecar"), () -> {
            callbackThread.set(Thread.currentThread().getName());
            completed.countDown();
        });
        sidecar.callback.get().run();

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotEquals(sidecarThread, callbackThread.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void sidecarFailureEmitsDiagnosticEvent() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher(SidecarResult.error("SIDECAR_EXIT_FAILED", "boom"));
        RuntimeScheduler scheduler = scheduler(events, sidecar);

        // When
        scheduler.dispatch(task("ai", "sidecar"), () -> { });

        // Then
        assertEquals(List.of(new PluginWorkBudgetEvent(
            PLUGIN_ID,
            "ai",
            PluginWorkBudgetEvent.Phase.FAILED,
            PluginWorkBudgetEvent.Decision.SIDECAR,
            PluginWorkBudgetEvent.Severity.ERROR
        )), events);
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler(
        List<PluginWorkBudgetEvent> events,
        SidecarDispatcher sidecarDispatcher
    ) {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 2, events::add, CLOCK),
            sidecarDispatcher,
            events::add
        );
    }

    private static PluginTask task(String type, String capability) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, capability);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingSidecarDispatcher implements SidecarDispatcher {

        private final AtomicReference<PluginTask> task = new AtomicReference<>();
        private final AtomicReference<Runnable> callback = new AtomicReference<>();
        private final SidecarResult result;

        private RecordingSidecarDispatcher() {
            this(SidecarResult.success(""));
        }

        private RecordingSidecarDispatcher(SidecarResult result) {
            this.result = result;
        }

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            this.task.set(task);
            this.callback.set(callback);
            return CompletableFuture.completedFuture(result);
        }
    }
}
