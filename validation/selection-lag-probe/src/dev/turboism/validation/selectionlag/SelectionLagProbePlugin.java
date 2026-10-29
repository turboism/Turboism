package dev.turboism.validation.selectionlag;

import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task-local selection-lag diagnostic probe (S4, host-ui-regressions-v1).
 *
 * <p>Observation-only EDT timing probe measuring EDT heartbeat latency, mouse event gaps,
 * top-stack captures during contention, and select-and-drag Robot interactions.</p>
 */
public final class SelectionLagProbePlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final long EDT_TIMEOUT_MILLIS = 5_000L;
    private static final int STACK_DEPTH = 64;
    private static final int CAPTURE_LIMIT = 40;
    private static final int CAPTURE_SHOTS = 3;
    private static final long CAPTURE_SHOT_GAP_MILLIS = 20L;
    private static final long HEARTBEAT_PERIOD_MILLIS = 25L;
    private static final long BASELINE_MILLIS = 5_000L;
    private static final long SETTLE_MILLIS = 3_000L;
    private static final long ATTEMPT_PACING_MILLIS = 1_200L;
    private static final long TRIGGER_COOLDOWN_MILLIS = 300L;

    @FunctionalInterface
    interface HostDispatcher {
        void dispatch(Runnable runnable);
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    enum Phase { BASELINE, ACTIVATION, INTERACTION, SETTLE }

    enum SelectionReadStatus { SUCCESS, TIMEOUT, FAILED }

    record SelectionEvidence(
            List<String> selectedIds,
            String activeArtMesh,
            SelectionReadStatus readStatus,
            long readDurationMillis) {
        boolean isSuccess() {
            return readStatus == SelectionReadStatus.SUCCESS;
        }
    }

    record HostWindowEvidence(Rectangle bounds, String title, String className, String boundsText) {}

    private final NanoClock clock;
    private final HostDispatcher hostDispatcher;

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

    public SelectionLagProbePlugin() {
        this(System::nanoTime, SwingUtilities::invokeLater);
    }

    SelectionLagProbePlugin(final NanoClock clock, final HostDispatcher hostDispatcher) {
        this.clock = clock;
        this.hostDispatcher = hostDispatcher;
    }

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread probe = new Thread(this::runWhenFlagged, "selection-lag-host-probe");
        probe.setDaemon(true);
        probe.start();
        logger.info("SELECTION_LAG_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("SELECTION_LAG_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("SELECTION_LAG_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("SELECTION_LAG_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FLAG_TIMEOUT_MILLIS);
        while (clock.nanoTime() < deadlineNanos) {
            if (Files.isRegularFile(flag)) {
                runProbe();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("SELECTION_LAG_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    // ------------------------------------------------------------------
    // Probe body
    // ------------------------------------------------------------------

    private void runProbe() {
        final long startedNanos = clock.nanoTime();
        final ProbeResult result = new ProbeResult();
        result.runId = System.getProperty("turboism.validation.runId", "unknown");
        result.hostVersion = System.getProperty("turboism.validation.hostVersion", "unknown");
        result.fixtureName = System.getProperty("turboism.validation.fixtureName", "unknown");
        result.probeStartedUtc = Instant.now().toString();
        final long thresholdMillis =
            Long.parseLong(System.getProperty("turboism.validation.thresholdMs", "100"));
        final int attempts =
            Integer.parseInt(System.getProperty("turboism.validation.attempts", "5"));

        try {
            final CubismModel model = awaitActiveModel();
            result.modelId = onHostThread(() -> model.id() == null ? "null" : model.id().value());
            result.drawableCount = onHostThread(() -> model.drawables().all().size());

            final long edtThreadId = findEdtThreadId(result);
            if (edtThreadId == -1L) {
                result.status = "BLOCKED";
                result.statusReason = "AWT-EventQueue thread not found";
                finishProbe(result, startedNanos);
                return;
            }
            final EdtSampler sampler = new EdtSampler(edtThreadId, thresholdMillis, clock, hostDispatcher);
            sampler.start();
            try {
                sampler.setPhase(Phase.BASELINE);
                Thread.sleep(BASELINE_MILLIS);

                final Robot robot = new Robot();
                final Rectangle hostWindow = awaitStableHostWindow(result);
                if (hostWindow == null) {
                    result.status = "BLOCKED";
                    result.statusReason = "no visible host window found for Robot interaction";
                    sampler.stop();
                    finishProbe(result, startedNanos);
                    return;
                }

                // Window activation is partitioned into ACTIVATION phase;
                // activation clicks do not count as drag INTERACTION.
                switchPhase(sampler, Phase.ACTIVATION);
                activateHostWindow(result.fixtureName);
                activateAndAwaitReceipt(robot, hostWindow, result.fixtureName, sampler);

                switchPhase(sampler, Phase.INTERACTION);
                runInteraction(robot, hostWindow, attempts, result, sampler);

                switchPhase(sampler, Phase.SETTLE);
                Thread.sleep(SETTLE_MILLIS);
            } finally {
                sampler.stop();
            }

            sampler.exportStats(result);
            result.probeFinishedUtc = Instant.now().toString();

            final boolean dragWorkloadDelivered =
                result.interactionCompletedGestures > 0 && result.interactionDragCount > 0;
            final boolean selectionChanged = result.selectionChangeCount > 0;
            final String selectionSummary;
            if (result.latestSelection != null && result.latestSelection.isSuccess()) {
                if (!result.latestSelection.selectedIds().isEmpty()) {
                    selectionSummary = "selection present (" + result.latestSelection.selectedIds().size() + " items)";
                } else {
                    selectionSummary = "selection empty";
                }
            } else {
                selectionSummary = "selection unknown (last read: "
                    + (result.latestSelection != null ? result.latestSelection.readStatus() : "none") + ")";
            }

            if (dragWorkloadDelivered) {
                result.status = "PASS";
                result.statusReason = "drag workload delivered (" + result.interactionCompletedGestures
                    + " gesture(s), " + result.interactionDragCount + " drag event(s)); "
                    + (selectionChanged ? "selection change observed (" + result.selectionChangeCount + " change(s)); " : "no selection change observed; ")
                    + selectionSummary;
            } else {
                result.status = "FAIL";
                result.statusReason = "no complete drag interaction delivered to EDT (drags: "
                    + result.interactionDragCount + ", gestures: " + result.interactionCompletedGestures
                    + ", activation events: " + result.activationMouseEvents + ")";
            }
        } catch (Exception failure) {
            logger.error("SELECTION_LAG_PROBE_FAILED " + singleLine(failure), failure);
            result.status = "BLOCKED";
            result.statusReason = singleLine(failure);
        }
        finishProbe(result, startedNanos);
    }

    void switchPhase(final EdtSampler sampler, final Phase nextPhase) throws Exception {
        // Execute phase switch on host thread to ensure prior queued events are drained,
        // and reset event gap and gesture tracking. Failure propagates to block probe.
        onHostThread(() -> {
            sampler.setPhase(nextPhase);
            sampler.resetEventGap();
            sampler.resetGestureState();
            return null;
        });
    }

    private void finishProbe(final ProbeResult result, final long startedNanos) {
        result.durationMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedNanos);
        writeEvidenceJson(result);
        writeResultFile(result);
        logger.info("SELECTION_LAG_PROBE_RESULT status=" + result.status
            + " runId=" + result.runId
            + " modelId=" + result.modelId
            + " drawableCount=" + result.drawableCount
            + " activationMouseEvents=" + result.activationMouseEvents
            + " interactionMouseEvents=" + result.interactionMouseEvents
            + " interactionCompletedGestures=" + result.interactionCompletedGestures
            + " interactionDragCount=" + result.interactionDragCount
            + " selectionChanges=" + result.selectionChangeCount
            + " overThresholdEvents=" + result.overThresholdCount
            + " turboismFrames=" + result.turboismFrameCounts.size()
            + " durationMillis=" + result.durationMillis);
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    private CubismModel awaitActiveModel() throws Exception {
        final long deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MODEL_AWAIT_MAX_MILLIS);
        Exception lastFailure = null;
        while (clock.nanoTime() < deadlineNanos) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                if (model != null) {
                    final boolean hasDrawables = onHostThread(() -> !model.drawables().all().isEmpty());
                    if (hasDrawables) {
                        return model;
                    }
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException(
            "No active model with drawables within " + MODEL_AWAIT_MAX_MILLIS + " ms", lastFailure);
    }

    // ------------------------------------------------------------------
    // EDT discovery and window discovery
    // ------------------------------------------------------------------

    private long findEdtThreadId(final ProbeResult result) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().matches("AWT-EventQueue-\\d+")) {
                result.edtThreadName = thread.getName();
                return thread.getId();
            }
        }
        return -1L;
    }

    private Rectangle awaitStableHostWindow(final ProbeResult result) throws Exception {
        final long deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(15_000L);
        Rectangle previous = null;
        int stableCount = 0;
        Rectangle latest = null;
        while (clock.nanoTime() < deadlineNanos) {
            final boolean readyForInput = onHostThread(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof java.awt.Dialog dialog
                            && dialog.isShowing()
                            && dialog.isModal()) {
                        return false;
                    }
                }
                final Window selected =
                    selectTargetWindow(Window.getWindows(), result.fixtureName);
                return selected != null && selected.isEnabled();
            });
            final HostWindowEvidence evidence = findHostWindow(result.fixtureName);
            latest = evidence != null ? evidence.bounds() : null;
            if (readyForInput && latest != null) {
                if (latest.equals(previous)) {
                    stableCount++;
                    if (stableCount >= 2) {
                        applyHostWindowEvidence(result, evidence);
                        return latest;
                    }
                } else {
                    previous = new Rectangle(latest);
                    stableCount = 1;
                }
            } else {
                previous = null;
                stableCount = 0;
            }
            Thread.sleep(150L);
        }
        final HostWindowEvidence finalEvidence = findHostWindow(result.fixtureName);
        if (finalEvidence != null) {
            applyHostWindowEvidence(result, finalEvidence);
            return finalEvidence.bounds();
        }
        return null;
    }

    private HostWindowEvidence findHostWindow(final String fixtureName) throws Exception {
        return onHostThread(() -> {
            final Window selected = selectTargetWindow(Window.getWindows(), fixtureName);
            if (selected == null) {
                return null;
            }
            final Rectangle bounds = selected.getBounds();
            if (bounds.isEmpty()) {
                return null;
            }
            final String title = selected instanceof java.awt.Frame f ? f.getTitle() : "";
            final String className = selected.getClass().getName();
            final String boundsText = bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height;
            return new HostWindowEvidence(bounds, title, className, boundsText);
        });
    }

    private static void applyHostWindowEvidence(final ProbeResult result, final HostWindowEvidence evidence) {
        if (evidence != null) {
            result.hostWindowTitle = evidence.title();
            result.hostWindowClass = evidence.className();
            result.hostWindowBounds = evidence.boundsText();
        }
    }

    static Window selectTargetWindow(final Window[] windows, final String fixtureName) {
        Window fixtureFrame = null;
        Window bestFrame = null;
        Window bestWindow = null;
        for (Window window : windows) {
            if (!window.isShowing()) {
                continue;
            }
            final Rectangle bounds = window.getBounds();
            if (bounds.isEmpty()) {
                continue;
            }
            if (window instanceof java.awt.Frame frame) {
                final String title = frame.getTitle() == null ? "" : frame.getTitle();
                if (fixtureName != null
                        && !fixtureName.isBlank()
                        && !"unknown".equals(fixtureName)
                        && title.contains(fixtureName)) {
                    if (fixtureFrame == null
                            || area(bounds) > area(fixtureFrame.getBounds())) {
                        fixtureFrame = window;
                    }
                }
                if (bestFrame == null || area(bounds) > area(bestFrame.getBounds())) {
                    bestFrame = window;
                }
            }
            if (bestWindow == null || area(bounds) > area(bestWindow.getBounds())) {
                bestWindow = window;
            }
        }
        if (fixtureFrame != null) {
            return fixtureFrame;
        }
        return bestFrame != null ? bestFrame : bestWindow;
    }

    private static long area(final Rectangle bounds) {
        return (long) bounds.width * (long) bounds.height;
    }

    // ------------------------------------------------------------------
    // Robot interaction: select-and-drag path
    // ------------------------------------------------------------------

    private void activateHostWindow(final String fixtureName) throws Exception {
        onHostThread(() -> {
            final Window target = selectTargetWindow(Window.getWindows(), fixtureName);
            if (target != null) {
                target.toFront();
                target.requestFocus();
            }
            return null;
        });
    }

    static Point titleBarActivationPoint(final Rectangle window) {
        final int centerX = window.x + window.width / 2;
        final int titleBarY = Math.max(window.y + 30, 20);
        return new Point(centerX, titleBarY);
    }

    private void activateAndAwaitReceipt(final Robot robot, final Rectangle window,
            final String fixtureName, final EdtSampler sampler) throws Exception {
        final Point titleBar = titleBarActivationPoint(window);
        final int centerX = window.x + window.width / 2;
        final int centerY = window.y + window.height / 2;
        final Point[] activationPoints = {
            titleBar,
            new Point(centerX, centerY),
            new Point(centerX, Math.max(window.y + 15, 12)),
            new Point(centerX, centerY),
        };
        for (int attempt = 0; attempt < activationPoints.length; attempt++) {
            activateHostWindow(fixtureName);
            final Point point = activationPoints[attempt];
            robot.mouseMove(point.x, point.y);
            robot.delay(150);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(250);
            robot.mouseMove(centerX, centerY);
            robot.delay(80);
            robot.mouseMove(centerX + 8, centerY + 8);
            robot.delay(150);
            if (sampler.activationMouseEventCount() > 0) {
                logger.info("SELECTION_LAG_ACTIVATION_RECEIPT attempt=" + attempt
                    + " activationMouseEvents=" + sampler.activationMouseEventCount());
                return;
            }
        }
    }

    private void runInteraction(final Robot robot, final Rectangle window, final int attempts,
            final ProbeResult result, final EdtSampler sampler) throws Exception {
        final Point[] offsets = {
            new Point(0, 0),
            new Point(window.width / 6, 0),
            new Point(-window.width / 6, 0),
            new Point(0, window.height / 6),
            new Point(0, -window.height / 6),
        };
        final int centerX = window.x + window.width / 2;
        final int centerY = window.y + window.height / 2;

        SelectionEvidence priorSelection = readSelectionBestEffort(() -> context.cubismRead().selection(), EDT_TIMEOUT_MILLIS);

        for (int index = 0; index < Math.min(attempts, offsets.length); index++) {
            final int beforeInteractionEvents = sampler.interactionMouseEventCount();
            final int beforeGestures = sampler.interactionCompletedGestures();
            final int beforeDrags = sampler.interactionDragCount();

            final Point offset = offsets[index];
            final int startX = centerX + offset.x;
            final int startY = centerY + offset.y;
            final long attemptStartNanos = clock.nanoTime();

            // Press at the target, then a short drag and release (select-and-drag path).
            robot.mouseMove(startX, startY);
            robot.delay(150);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            for (int step = 1; step <= 3; step++) {
                robot.mouseMove(startX + step * 4, startY + step * 4);
                robot.delay(30);
            }
            robot.delay(60);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            final long attemptMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - attemptStartNanos);
            Thread.sleep(400L);

            // 1. Immediately read selection following drag completion:
            final SelectionEvidence afterSelection =
                readSelectionBestEffort(() -> context.cubismRead().selection(), EDT_TIMEOUT_MILLIS);
            final boolean selectionChanged = computeSelectionChanged(priorSelection, afterSelection);
            if (selectionChanged) {
                result.selectionChangeCount++;
            }
            result.latestSelection = afterSelection;

            // 2. Record this attempt with exact interaction input deltas and full before/after selection states:
            final int attemptEvents = sampler.interactionMouseEventCount() - beforeInteractionEvents;
            final int attemptGestures = sampler.interactionCompletedGestures() - beforeGestures;
            final int attemptDrags = sampler.interactionDragCount() - beforeDrags;

            final InteractionAttempt record = new InteractionAttempt(index, startX, startY,
                attemptMillis,
                priorSelection.selectedIds(), priorSelection.activeArtMesh(), priorSelection.readStatus().name(),
                afterSelection.selectedIds(), afterSelection.activeArtMesh(), afterSelection.readStatus().name(),
                afterSelection.readDurationMillis(),
                selectionChanged, attemptEvents, attemptGestures, attemptDrags,
                sampler.interactionMouseEventCount());
            result.interactionAttempts.add(record);

            logger.info("SELECTION_LAG_ATTEMPT index=" + index
                + " point=" + startX + "," + startY
                + " robotMillis=" + record.robotMillis
                + " attemptEvents=" + attemptEvents
                + " attemptGestures=" + attemptGestures
                + " attemptDrags=" + attemptDrags
                + " mouseEventsTotal=" + record.mouseEventsTotal
                + " selectedBefore=" + record.selectedObjectIdsBefore
                + " selectedAfter=" + record.selectedObjectIdsAfter
                + " activeArtMeshAfter=" + record.activeArtMeshAfter
                + " selectionChanged=" + record.selectionChanged
                + " readStatusAfter=" + record.selectionReadStatusAfter
                + " readDurationMs=" + record.selectionReadDurationMs);

            priorSelection = afterSelection;

            // 3. If no interaction events reached the EDT, reactivate in ACTIVATION phase
            // and refresh priorSelection baseline:
            if (attemptEvents == 0) {
                switchPhase(sampler, Phase.ACTIVATION);
                activateAndAwaitReceipt(robot, window, result.fixtureName, sampler);
                switchPhase(sampler, Phase.INTERACTION);
                priorSelection = readSelectionBestEffort(() -> context.cubismRead().selection(), EDT_TIMEOUT_MILLIS);
            }

            final long elapsedAttemptMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - attemptStartNanos);
            final long remaining = ATTEMPT_PACING_MILLIS - elapsedAttemptMillis;
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
        }
    }

    static boolean computeSelectionChanged(final SelectionEvidence before, final SelectionEvidence after) {
        if (!before.isSuccess() || !after.isSuccess()) {
            return false;
        }
        final Set<String> beforeIds = Set.copyOf(before.selectedIds());
        final Set<String> afterIds = Set.copyOf(after.selectedIds());
        if (!beforeIds.equals(afterIds)) {
            return true;
        }
        return !Objects.equals(before.activeArtMesh(), after.activeArtMesh());
    }

    SelectionEvidence readSelectionBestEffort(final Callable<SelectionSnapshot> reader, final long timeoutMillis) {
        final long startNanos = clock.nanoTime();
        try {
            final SelectionSnapshot snapshot = onHostThread(reader, timeoutMillis);
            final List<String> ids = List.copyOf(snapshot.selectedObjectIds());
            final String mesh = snapshot.activeArtMeshId().orElse("");
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos);
            return new SelectionEvidence(ids, mesh, SelectionReadStatus.SUCCESS, elapsedMillis);
        } catch (TimeoutException timedOut) {
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos);
            return new SelectionEvidence(List.of(), "", SelectionReadStatus.TIMEOUT, elapsedMillis);
        } catch (Exception failed) {
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos);
            return new SelectionEvidence(List.of(), "", SelectionReadStatus.FAILED, elapsedMillis);
        }
    }

    // ------------------------------------------------------------------
    // EDT sampler
    // ------------------------------------------------------------------

    static final class AmortizedSamples {
        private long[] data = new long[32];
        private int count = 0;

        void add(final long value) {
            if (count == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[count++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(data, count);
        }

        int size() {
            return count;
        }
    }

    static final class PhaseStatsBuilder {
        final AmortizedSamples gapSamples = new AmortizedSamples();
        final AmortizedSamples latencySamples = new AmortizedSamples();
        int eventCount = 0;
        int pressCount = 0;
        int dragCount = 0;
        int releaseCount = 0;
        int clickCount = 0;
        int moveCount = 0;
        int completedGestures = 0;
    }

    static final class EdtSampler {

        private final long edtThreadId;
        private final long thresholdNanos;
        private final NanoClock clock;
        private final HostDispatcher dispatcher;
        private final ThreadMXBean bean;

        private final Object stateLock = new Object();
        private boolean running = false;
        private Phase phase = Phase.BASELINE;

        private boolean hasLastEvent = false;
        private long lastEventNanos = 0L;

        private boolean currentInGesture = false;
        private boolean currentGestureHadDrag = false;

        private boolean heartbeatPending = false;
        private long heartbeatEnqueueNanos = 0L;
        private Phase heartbeatEnqueuePhase = Phase.BASELINE;

        private long pendingTriggerMillis = -1L;
        private String pendingTriggerReason = "";
        private Phase pendingTriggerPhase = Phase.BASELINE;

        private boolean hasCaptured = false;
        private long lastCaptureNanos = 0L;

        private int activationMouseEvents = 0;
        private int interactionMouseEvents = 0;

        private final List<PhaseStatsBuilder> phaseBuilders = new ArrayList<>();
        private final List<StackCapture> captures = new ArrayList<>();
        private final Map<String, Integer> turboismFrameCounts = new HashMap<>();

        private AWTEventListener awtListener;
        private ScheduledExecutorService heartbeat;
        private Thread watchdog;

        EdtSampler(final long edtThreadId, final long thresholdMillis, final NanoClock clock,
                final HostDispatcher dispatcher, final ThreadMXBean bean) {
            this.edtThreadId = edtThreadId;
            this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMillis);
            this.clock = clock;
            this.dispatcher = dispatcher;
            this.bean = bean;
            for (Phase ignored : Phase.values()) {
                phaseBuilders.add(new PhaseStatsBuilder());
            }
        }

        EdtSampler(final long edtThreadId, final long thresholdMillis, final NanoClock clock,
                final HostDispatcher dispatcher) {
            this(edtThreadId, thresholdMillis, clock, dispatcher, ManagementFactory.getThreadMXBean());
        }

        void setPhase(final Phase next) {
            synchronized (stateLock) {
                phase = next;
            }
        }

        Phase getPhase() {
            synchronized (stateLock) {
                return phase;
            }
        }

        void resetEventGap() {
            synchronized (stateLock) {
                hasLastEvent = false;
                lastEventNanos = clock.nanoTime();
            }
        }

        void resetGestureState() {
            synchronized (stateLock) {
                currentInGesture = false;
                currentGestureHadDrag = false;
            }
        }

        int activationMouseEventCount() {
            synchronized (stateLock) {
                return activationMouseEvents;
            }
        }

        int interactionMouseEventCount() {
            synchronized (stateLock) {
                return interactionMouseEvents;
            }
        }

        int interactionCompletedGestures() {
            synchronized (stateLock) {
                return phaseBuilders.get(Phase.INTERACTION.ordinal()).completedGestures;
            }
        }

        int interactionDragCount() {
            synchronized (stateLock) {
                return phaseBuilders.get(Phase.INTERACTION.ordinal()).dragCount;
            }
        }

        void startObservation() {
            synchronized (stateLock) {
                running = true;
                hasLastEvent = false;
                lastEventNanos = clock.nanoTime();
                hasCaptured = false;
            }
            awtListener = event -> onMouseEvent(event.getID());
            Toolkit.getDefaultToolkit().addAWTEventListener(
                awtListener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }

        void start() {
            startObservation();

            heartbeat = new ScheduledThreadPoolExecutor(1, runnable -> {
                final Thread thread = new Thread(runnable, "selection-lag-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeat.scheduleAtFixedRate(
                this::heartbeatTick, HEARTBEAT_PERIOD_MILLIS, HEARTBEAT_PERIOD_MILLIS, TimeUnit.MILLISECONDS);

            watchdog = new Thread(this::watchdogLoop, "selection-lag-stack-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        void onMouseEvent(final int eventId) {
            final long nowNanos = clock.nanoTime();
            synchronized (stateLock) {
                if (!running) {
                    return;
                }
                final long gapNanos;
                if (hasLastEvent) {
                    gapNanos = nowNanos - lastEventNanos;
                } else {
                    gapNanos = 0L;
                    hasLastEvent = true;
                }
                lastEventNanos = nowNanos;
                final long gapMillis = TimeUnit.NANOSECONDS.toMillis(gapNanos);
                final Phase current = phase;
                final PhaseStatsBuilder stats = phaseBuilders.get(current.ordinal());
                stats.gapSamples.add(gapMillis);
                stats.eventCount++;

                if (current == Phase.ACTIVATION) {
                    activationMouseEvents++;
                } else if (current == Phase.INTERACTION) {
                    interactionMouseEvents++;
                }

                switch (eventId) {
                    case MouseEvent.MOUSE_PRESSED -> {
                        stats.pressCount++;
                        currentInGesture = true;
                        currentGestureHadDrag = false;
                    }
                    case MouseEvent.MOUSE_DRAGGED -> {
                        stats.dragCount++;
                        if (currentInGesture) {
                            currentGestureHadDrag = true;
                        }
                    }
                    case MouseEvent.MOUSE_RELEASED -> {
                        stats.releaseCount++;
                        if (currentInGesture && currentGestureHadDrag) {
                            stats.completedGestures++;
                        }
                        currentInGesture = false;
                        currentGestureHadDrag = false;
                    }
                    case MouseEvent.MOUSE_CLICKED -> stats.clickCount++;
                    case MouseEvent.MOUSE_MOVED -> stats.moveCount++;
                    default -> {}
                }

                if (hasLastEvent && gapNanos >= thresholdNanos) {
                    triggerWatchdogLocked("EVENT_GAP", gapMillis, current);
                }
            }
        }

        void heartbeatTick() {
            final long nowNanos = clock.nanoTime();
            synchronized (stateLock) {
                if (!running) {
                    return;
                }
                if (!heartbeatPending) {
                    heartbeatPending = true;
                    heartbeatEnqueueNanos = nowNanos;
                    heartbeatEnqueuePhase = phase;
                } else {
                    final long blockedNanos = nowNanos - heartbeatEnqueueNanos;
                    if (blockedNanos >= thresholdNanos) {
                        final long blockedMillis = TimeUnit.NANOSECONDS.toMillis(blockedNanos);
                        triggerWatchdogLocked("HEARTBEAT_BLOCKED", blockedMillis, heartbeatEnqueuePhase);
                    }
                    return;
                }
            }

            dispatcher.dispatch(() -> {
                final long finishNanos = clock.nanoTime();
                synchronized (stateLock) {
                    if (!running || !heartbeatPending) {
                        heartbeatPending = false;
                        return;
                    }
                    final long enqueueNanos = heartbeatEnqueueNanos;
                    final Phase enqueuePhase = heartbeatEnqueuePhase;
                    heartbeatPending = false;
                    final long latencyNanos = finishNanos - enqueueNanos;
                    final long latencyMillis = TimeUnit.NANOSECONDS.toMillis(latencyNanos);
                    phaseBuilders.get(enqueuePhase.ordinal()).latencySamples.add(latencyMillis);
                    if (latencyNanos >= thresholdNanos) {
                        triggerWatchdogLocked("HEARTBEAT", latencyMillis, enqueuePhase);
                    }
                }
            });
        }

        private void triggerWatchdogLocked(final String reason, final long millis, final Phase triggerPhase) {
            pendingTriggerMillis = millis;
            pendingTriggerReason = reason;
            pendingTriggerPhase = triggerPhase;
        }

        void watchdogTick() {
            final String reason;
            final long millis;
            final Phase triggerPhase;
            synchronized (stateLock) {
                if (!running || pendingTriggerMillis < 0) {
                    return;
                }
                reason = pendingTriggerReason;
                millis = pendingTriggerMillis;
                triggerPhase = pendingTriggerPhase;
                pendingTriggerMillis = -1;
                pendingTriggerReason = "";
            }

            final long nowNanos = clock.nanoTime();
            boolean shouldCapture = false;
            synchronized (stateLock) {
                if (running && (!hasCaptured || nowNanos - lastCaptureNanos >= TimeUnit.MILLISECONDS.toNanos(TRIGGER_COOLDOWN_MILLIS))) {
                    hasCaptured = true;
                    lastCaptureNanos = nowNanos;
                    shouldCapture = true;
                }
            }
            if (shouldCapture) {
                captureStack(reason, millis, triggerPhase);
            }
        }

        private void watchdogLoop() {
            while (true) {
                synchronized (stateLock) {
                    if (!running) {
                        return;
                    }
                }
                watchdogTick();
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void captureStack(final String reason, final long triggerMillis, final Phase triggerPhase) {
            final String capturedPhase = triggerPhase.name();
            for (int shot = 0; shot < CAPTURE_SHOTS; shot++) {
                synchronized (stateLock) {
                    if (!running || captures.size() >= CAPTURE_LIMIT) {
                        return;
                    }
                }
                final ThreadInfo info = bean.getThreadInfo(new long[] {edtThreadId}, STACK_DEPTH)[0];
                if (info == null) {
                    return;
                }
                final StackTraceElement[] stack = info.getStackTrace();
                final List<String> frames = new ArrayList<>();
                final List<String> turboismFrames = new ArrayList<>();
                for (StackTraceElement frame : stack) {
                    final String line = frame.getClassName() + "." + frame.getMethodName()
                        + (frame.getLineNumber() >= 0 ? ":" + frame.getLineNumber() : "");
                    frames.add(line);
                    if (frame.getClassName().startsWith("dev.turboism.")) {
                        turboismFrames.add(line);
                    }
                }

                synchronized (stateLock) {
                    if (!running || captures.size() >= CAPTURE_LIMIT) {
                        return;
                    }
                    captures.add(new StackCapture(Instant.now().toString(), reason, triggerMillis,
                        capturedPhase, info.getThreadState().name(), frames, turboismFrames));
                    for (String line : turboismFrames) {
                        turboismFrameCounts.merge(line, 1, Integer::sum);
                    }
                }

                try {
                    Thread.sleep(CAPTURE_SHOT_GAP_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            if (awtListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
                awtListener = null;
            }
            synchronized (stateLock) {
                running = false;
            }
            if (heartbeat != null) {
                heartbeat.shutdownNow();
                try {
                    heartbeat.awaitTermination(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (watchdog != null) {
                watchdog.interrupt();
                try {
                    watchdog.join(500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void exportStats(final ProbeResult result) {
            synchronized (stateLock) {
                for (Phase value : Phase.values()) {
                    final PhaseStatsBuilder builder = phaseBuilders.get(value.ordinal());
                    final long[] gapSamples = builder.gapSamples.toArray();
                    final long[] latencySamples = builder.latencySamples.toArray();
                    result.phases.put(value.name(), new PhaseStats(
                        builder.eventCount,
                        percentiles(gapSamples),
                        latencySamples.length,
                        percentiles(latencySamples)));
                }
                result.activationMouseEvents = activationMouseEvents;
                result.interactionMouseEvents = interactionMouseEvents;
                result.interactionCompletedGestures = phaseBuilders.get(Phase.INTERACTION.ordinal()).completedGestures;
                result.interactionDragCount = phaseBuilders.get(Phase.INTERACTION.ordinal()).dragCount;
                result.overThresholdCount = captures.size();
                result.stackCaptures = List.copyOf(captures);
                result.turboismFrameCounts = new HashMap<>(turboismFrameCounts);
            }
        }

        private static long[] percentiles(final long[] samples) {
            if (samples.length == 0) {
                return new long[] {-1, -1, -1};
            }
            final long[] sorted = samples.clone();
            Arrays.sort(sorted);
            final long p50 = sorted[(int) (sorted.length * 0.50)];
            final long p95 = sorted[(int) Math.min(sorted.length - 1, sorted.length * 0.95)];
            final long max = sorted[sorted.length - 1];
            return new long[] {p50, p95, max};
        }
    }

    // ------------------------------------------------------------------
    // Evidence records
    // ------------------------------------------------------------------

    record StackCapture(String utc, String trigger, long triggerMillis, String phase,
            String edtState, List<String> frames, List<String> turboismFrames) {
    }

    record PhaseStats(long eventCount, long[] eventGapMillis,
            long heartbeatCount, long[] heartbeatLatencyMillis) {
    }

    record InteractionAttempt(
            int index,
            int x, int y,
            long robotMillis,
            List<String> selectedObjectIdsBefore,
            String activeArtMeshBefore,
            String selectionReadStatusBefore,
            List<String> selectedObjectIdsAfter,
            String activeArtMeshAfter,
            String selectionReadStatusAfter,
            long selectionReadDurationMs,
            boolean selectionChanged,
            int attemptEvents,
            int attemptGestures,
            int attemptDrags,
            int mouseEventsTotal) {
    }

    static final class ProbeResult {
        String runId;
        String hostVersion;
        String fixtureName;
        String probeStartedUtc;
        String probeFinishedUtc = "";
        String modelId = "unknown";
        int drawableCount = -1;
        String edtThreadName = "";
        String hostWindowTitle = "";
        String hostWindowClass = "";
        String hostWindowBounds = "";
        final List<InteractionAttempt> interactionAttempts = new ArrayList<>();
        final Map<String, PhaseStats> phases = new HashMap<>();
        int activationMouseEvents;
        int interactionMouseEvents;
        int interactionCompletedGestures;
        int interactionDragCount;
        int selectionChangeCount;
        SelectionEvidence latestSelection = null;
        int overThresholdCount;
        List<StackCapture> stackCaptures = List.of();
        Map<String, Integer> turboismFrameCounts = new HashMap<>();
        long durationMillis;
        String status = "BLOCKED";
        String statusReason = "probe did not finish";
    }

    // ------------------------------------------------------------------
    // Host thread helper with FutureTask, bounded wait, and cancellation
    // ------------------------------------------------------------------

    <T> T onHostThread(final Callable<T> operation) throws Exception {
        return onHostThread(operation, EDT_TIMEOUT_MILLIS);
    }

    <T> T onHostThread(final Callable<T> operation, final long timeoutMillis) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        final FutureTask<T> future = new FutureTask<>(operation);
        hostDispatcher.dispatch(future);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            future.cancel(false);
            throw timedOut;
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException executionException) {
            final Throwable cause = executionException.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void writeResultFile(final ProbeResult result) {
        final Path resultFile = stateDir.getParent().resolve("selection-lag-result.properties");
        try {
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=").append(result.runId).append('\n')
                .append("pluginId=dev.turboism.validation.selection-lag\n")
                .append("hostVersion=").append(result.hostVersion).append('\n')
                .append("fixtureName=").append(result.fixtureName).append('\n')
                .append("modelId=").append(result.modelId).append('\n')
                .append("drawableCount=").append(result.drawableCount).append('\n')
                .append("edtThreadName=").append(result.edtThreadName).append('\n')
                .append("hostWindowBounds=").append(result.hostWindowBounds).append('\n')
                .append("interactionAttempts=").append(result.interactionAttempts.size()).append('\n')
                .append("activationMouseEvents=").append(result.activationMouseEvents).append('\n')
                .append("interactionMouseEvents=").append(result.interactionMouseEvents).append('\n')
                .append("interactionCompletedGestures=").append(result.interactionCompletedGestures).append('\n')
                .append("interactionDragCount=").append(result.interactionDragCount).append('\n')
                .append("selectionChanges=").append(result.selectionChangeCount).append('\n')
                .append("overThresholdEvents=").append(result.overThresholdCount).append('\n')
                .append("turboismFrameCounts=").append(result.turboismFrameCounts.size()).append('\n')
                .append("durationMillis=").append(result.durationMillis).append('\n')
                .append("statusReason=").append(singleLine(result.statusReason)).append('\n')
                .append("status=").append(result.status).append('\n');
            Files.createDirectories(resultFile.getParent());
            Files.writeString(resultFile, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("SELECTION_LAG_RESULT_WRITTEN result=" + resultFile
                + " status=" + result.status);
        } catch (Exception failure) {
            logger.error("SELECTION_LAG_RESULT_WRITE_FAILED result=" + resultFile
                + " " + singleLine(failure), failure);
        }
    }

    private void writeEvidenceJson(final ProbeResult result) {
        final Path evidence = stateDir.resolve("selection-lag-evidence-" + result.runId + ".json");
        try {
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, toJson(result),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("SELECTION_LAG_EVIDENCE_WRITTEN evidence=" + evidence
                + " bytes=" + Files.size(evidence));
        } catch (Exception failure) {
            logger.error("SELECTION_LAG_EVIDENCE_WRITE_FAILED evidence=" + evidence
                + " " + singleLine(failure), failure);
        }
    }

    private static String toJson(final ProbeResult result) {
        final StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, "schemaVersion", 1);
        field(json, "runId", result.runId);
        field(json, "hostVersion", result.hostVersion);
        field(json, "fixtureName", result.fixtureName);
        field(json, "probeStartedUtc", result.probeStartedUtc);
        field(json, "probeFinishedUtc", result.probeFinishedUtc);
        field(json, "status", result.status);
        field(json, "statusReason", result.statusReason);
        field(json, "modelId", result.modelId);
        field(json, "drawableCount", result.drawableCount);
        field(json, "edtThreadName", result.edtThreadName);
        field(json, "hostWindowTitle", result.hostWindowTitle);
        field(json, "hostWindowClass", result.hostWindowClass);
        field(json, "hostWindowBounds", result.hostWindowBounds);
        field(json, "durationMillis", result.durationMillis);
        field(json, "activationMouseEvents", result.activationMouseEvents);
        field(json, "interactionMouseEvents", result.interactionMouseEvents);
        field(json, "interactionCompletedGestures", result.interactionCompletedGestures);
        field(json, "interactionDragCount", result.interactionDragCount);
        field(json, "selectionChanges", result.selectionChangeCount);
        field(json, "overThresholdEvents", result.overThresholdCount);

        json.append("  \"interactionAttempts\": [\n");
        for (int index = 0; index < result.interactionAttempts.size(); index++) {
            final InteractionAttempt attempt = result.interactionAttempts.get(index);
            json.append("    {\"index\": ").append(attempt.index())
                .append(", \"x\": ").append(attempt.x())
                .append(", \"y\": ").append(attempt.y())
                .append(", \"robotMillis\": ").append(attempt.robotMillis())
                .append(", \"attemptEvents\": ").append(attempt.attemptEvents())
                .append(", \"attemptGestures\": ").append(attempt.attemptGestures())
                .append(", \"attemptDrags\": ").append(attempt.attemptDrags())
                .append(", \"mouseEventsTotal\": ").append(attempt.mouseEventsTotal())
                .append(", \"selectedObjectIdsBefore\": ").append(jsonArray(attempt.selectedObjectIdsBefore()))
                .append(", \"activeArtMeshBefore\": \"").append(escape(attempt.activeArtMeshBefore())).append("\"")
                .append(", \"selectionReadStatusBefore\": \"").append(escape(attempt.selectionReadStatusBefore())).append("\"")
                .append(", \"selectedObjectIdsAfter\": ").append(jsonArray(attempt.selectedObjectIdsAfter()))
                .append(", \"activeArtMeshAfter\": \"").append(escape(attempt.activeArtMeshAfter())).append("\"")
                .append(", \"selectionReadStatusAfter\": \"").append(escape(attempt.selectionReadStatusAfter())).append("\"")
                .append(", \"selectionReadDurationMs\": ").append(attempt.selectionReadDurationMs())
                .append(", \"selectionChanged\": ").append(attempt.selectionChanged());
            if (index + 1 < result.interactionAttempts.size()) {
                json.append("},\n");
            } else {
                json.append("}\n");
            }
        }
        json.append("  ],\n");

        json.append("  \"phases\": {\n");
        final List<String> phaseNames = new ArrayList<>(result.phases.keySet());
        phaseNames.sort(String::compareTo);
        for (int index = 0; index < phaseNames.size(); index++) {
            final String name = phaseNames.get(index);
            final PhaseStats stats = result.phases.get(name);
            json.append("    \"").append(name).append("\": {")
                .append("\"eventCount\": ").append(stats.eventCount())
                .append(", \"eventGapMillis\": {\"p50\": ").append(stats.eventGapMillis()[0])
                .append(", \"p95\": ").append(stats.eventGapMillis()[1])
                .append(", \"max\": ").append(stats.eventGapMillis()[2]).append("}")
                .append(", \"heartbeatCount\": ").append(stats.heartbeatCount())
                .append(", \"heartbeatLatencyMillis\": {\"p50\": ").append(stats.heartbeatLatencyMillis()[0])
                .append(", \"p95\": ").append(stats.heartbeatLatencyMillis()[1])
                .append(", \"max\": ").append(stats.heartbeatLatencyMillis()[2]).append("}");
            if (index + 1 < phaseNames.size()) {
                json.append("},\n");
            } else {
                json.append("}\n");
            }
        }
        json.append("  },\n");

        json.append("  \"stackCaptures\": [\n");
        for (int index = 0; index < result.stackCaptures.size(); index++) {
            final StackCapture capture = result.stackCaptures.get(index);
            json.append("    {\"utc\": \"").append(capture.utc())
                .append("\", \"trigger\": \"").append(capture.trigger())
                .append("\", \"triggerMillis\": ").append(capture.triggerMillis())
                .append(", \"phase\": \"").append(capture.phase())
                .append("\", \"edtState\": \"").append(capture.edtState()).append("\",\n");
            json.append("      \"frames\": [\n");
            for (int frameIndex = 0; frameIndex < capture.frames().size(); frameIndex++) {
                json.append("        \"").append(escape(capture.frames().get(frameIndex))).append("\"");
                if (frameIndex + 1 < capture.frames().size()) {
                    json.append(",\n");
                } else {
                    json.append("\n");
                }
            }
            json.append("      ],\n");
            json.append("      \"turboismFrames\": [");
            for (int frameIndex = 0; frameIndex < capture.turboismFrames().size(); frameIndex++) {
                json.append("\"").append(escape(capture.turboismFrames().get(frameIndex))).append("\"");
                if (frameIndex + 1 < capture.turboismFrames().size()) {
                    json.append(", ");
                }
            }
            json.append("]");
            if (index + 1 < result.stackCaptures.size()) {
                json.append("},\n");
            } else {
                json.append("}\n");
            }
        }
        json.append("  ],\n");

        json.append("  \"turboismFrameCounts\": {\n");
        final List<Map.Entry<String, Integer>> entries =
            new ArrayList<>(result.turboismFrameCounts.entrySet());
        entries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        for (int index = 0; index < entries.size(); index++) {
            final Map.Entry<String, Integer> entry = entries.get(index);
            json.append("    \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            if (index + 1 < entries.size()) {
                json.append(",\n");
            } else {
                json.append("\n");
            }
        }
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static String jsonArray(final List<String> items) {
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            builder.append('"').append(escape(items.get(i))).append('"');
            if (i + 1 < items.size()) {
                builder.append(", ");
            }
        }
        builder.append(']');
        return builder.toString();
    }

    private static void field(final StringBuilder json, final String name, final String value) {
        json.append("  \"").append(name).append("\": \"").append(escape(value)).append("\",\n");
    }

    private static void field(final StringBuilder json, final String name, final long value) {
        json.append("  \"").append(name).append("\": ").append(value).append(",\n");
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
