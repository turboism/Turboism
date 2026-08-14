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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local selection-lag diagnostic probe (S4, host-ui-regressions-v1).
 *
 * <p>Purpose: capture machine evidence for the user-reported "selecting an
 * object briefly stalls, dragging is hard, then recovers" regression on the
 * exact Cubism host. The probe is observation-only:</p>
 *
 * <ul>
 *   <li>EDT dispatch timing: an {@link AWTEventListener} records every mouse
 *       press/drag/release dispatch on the EDT; the gap between two consecutive
 *       dispatch callbacks upper-bounds the EDT time consumed by the previous
 *       event plus queueing. A 10 ms {@code invokeLater} heartbeat records
 *       enqueue-to-run latency as an independent EDT-busy signal.</li>
 *   <li>Top-stack capture: when an event gap or heartbeat latency exceeds the
 *       threshold (default 100 ms), a watchdog thread snapshots the EDT stack
 *       via {@link ThreadMXBean#getThreadInfo(long, int, boolean)} (three shots
 *       at 20 ms spacing) and marks any {@code dev.turboism.*} frames.</li>
 *   <li>Interaction: {@link java.awt.Robot} performs press-drag-release
 *       sequences inside the main Cubism window (select-and-drag path). After
 *       each attempt the probe reads the SDK selection snapshot as best-effort
 *       evidence of whether a selection occurred. The probe never saves,
 *       never writes the model, and never creates Undo through any API.</li>
 * </ul>
 *
 * <p>This probe is validation tooling only. It is never part of the production
 * preview bundle or product build.</p>
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

    private enum Phase { BASELINE, INTERACTION, SETTLE }

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

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
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
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
        final long startedMillis = System.currentTimeMillis();
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
                finishProbe(result, startedMillis);
                return;
            }
            final EdtSampler sampler = new EdtSampler(edtThreadId, thresholdMillis);
            sampler.start();
            try {
                sampler.setPhase(Phase.BASELINE);
                Thread.sleep(BASELINE_MILLIS);

                final Robot robot = new Robot();
                final Rectangle hostWindow = findHostWindow(result);
                if (hostWindow == null) {
                    result.status = "BLOCKED";
                    result.statusReason = "no visible host window found for Robot interaction";
                    sampler.stop();
                    finishProbe(result, startedMillis);
                    return;
                }
                sampler.setPhase(Phase.INTERACTION);
                sampler.resetEventGap();
                activateHostWindow();
                runInteraction(robot, hostWindow, attempts, result, sampler);
                sampler.setPhase(Phase.SETTLE);
                Thread.sleep(SETTLE_MILLIS);
            } finally {
                sampler.stop();
            }

            sampler.exportStats(result);
            result.probeFinishedUtc = Instant.now().toString();
            result.status = result.interactionMouseEvents > 0 ? "PASS" : "FAIL";
            result.statusReason = result.interactionMouseEvents > 0
                ? "probe executed; interaction mouse events were observed by the EDT sampler"
                : "no mouse events observed during interaction (input did not reach the EDT)";
        } catch (Exception failure) {
            logger.error("SELECTION_LAG_PROBE_FAILED " + singleLine(failure), failure);
            result.status = "BLOCKED";
            result.statusReason = singleLine(failure);
        }
        finishProbe(result, startedMillis);
    }

    private void finishProbe(final ProbeResult result, final long startedMillis) {
        result.durationMillis = System.currentTimeMillis() - startedMillis;
        writeEvidenceJson(result);
        writeResultFile(result);
        logger.info("SELECTION_LAG_PROBE_RESULT status=" + result.status
            + " runId=" + result.runId
            + " modelId=" + result.modelId
            + " drawableCount=" + result.drawableCount
            + " interactionMouseEvents=" + result.interactionMouseEvents
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
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
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
        // Exact EDT name match: JOGL spawns "AWT-EventQueue-0-SharedResourceRunner"
        // and similar prefixed threads which must not be mistaken for the EDT.
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().matches("AWT-EventQueue-\\d+")) {
                result.edtThreadName = thread.getName();
                return thread.getId();
            }
        }
        return -1L;
    }

    private Rectangle findHostWindow(final ProbeResult result) throws Exception {
        return onHostThread(() -> {
            Rectangle best = null;
            String bestTitle = "";
            String bestClass = "";
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) {
                    continue;
                }
                final Rectangle bounds = window.getBounds();
                if (bounds.isEmpty()) {
                    continue;
                }
                if (best == null || bounds.width * bounds.height > best.width * best.height) {
                    best = bounds;
                    bestTitle = window instanceof java.awt.Frame f ? f.getTitle() : "";
                    bestClass = window.getClass().getName();
                }
            }
            if (best != null) {
                result.hostWindowTitle = bestTitle;
                result.hostWindowClass = bestClass;
                result.hostWindowBounds = best.x + "," + best.y + " " + best.width + "x" + best.height;
            }
            return best;
        });
    }

    // ------------------------------------------------------------------
    // Robot interaction: select-and-drag path
    // ------------------------------------------------------------------


    private void activateHostWindow() throws Exception {
        onHostThread(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing()) {
                    window.toFront();
                }
            }
            return null;
        });
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
        // Wine-hosted windows may ignore input while unactivated: perform one
        // press+release on the window title bar before the first attempt.
        robot.mouseMove(centerX, window.y + 30);
        robot.delay(200);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(500);
        for (int index = 0; index < Math.min(attempts, offsets.length); index++) {
            final Point offset = offsets[index];
            final int startX = centerX + offset.x;
            final int startY = centerY + offset.y;
            final long attemptStart = System.currentTimeMillis();
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
            final long attemptMillis = System.currentTimeMillis() - attemptStart;
            Thread.sleep(400L);
            final SelectionEvidence selection = readSelectionBestEffort();
            final InteractionAttempt record = new InteractionAttempt(index, startX, startY,
                attemptMillis, selection.selectedIds, selection.activeArtMesh,
                selection.readTimedOutMillis, sampler.mouseEventCount());
            result.interactionAttempts.add(record);
            logger.info("SELECTION_LAG_ATTEMPT index=" + index
                + " point=" + startX + "," + startY
                + " robotMillis=" + record.robotMillis
                + " mouseEventsTotal=" + record.mouseEventsTotal
                + " selectedAfter=" + record.selectedObjectIdsAfter
                + " activeArtMeshAfter=" + record.activeArtMeshAfter
                + " selectionReadTimedOutMs=" + record.selectionReadTimedOutMs);
            final long remaining = ATTEMPT_PACING_MILLIS
                - (System.currentTimeMillis() - attemptStart);
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
        }
    }

    private record SelectionEvidence(List<String> selectedIds, String activeArtMesh,
            long readTimedOutMillis) {
    }

    private SelectionEvidence readSelectionBestEffort() {
        final AtomicReference<List<String>> ids = new AtomicReference<>(List.of());
        final AtomicReference<String> mesh = new AtomicReference<>("");
        final AtomicReference<Long> timedOut = new AtomicReference<>(0L);
        try {
            onHostThread(() -> {
                try {
                    final SelectionSnapshot snapshot = context.cubismRead().selection();
                    ids.set(snapshot.selectedObjectIds());
                    mesh.set(snapshot.activeArtMeshId().orElse(""));
                } catch (RuntimeException unavailable) {
                    logger.warn("SELECTION_LAG_SELECTION_READ_FAILED " + singleLine(unavailable));
                }
                return null;
            });
        } catch (Exception timedOutReading) {
            timedOut.set(EDT_TIMEOUT_MILLIS);
            logger.warn("SELECTION_LAG_SELECTION_READ_TIMEOUT " + singleLine(timedOutReading));
        }
        return new SelectionEvidence(ids.get(), mesh.get(), timedOut.get());
    }

    // ------------------------------------------------------------------
    // EDT sampler
    // ------------------------------------------------------------------

    /**
     * Observation-only EDT timing sampler. All callback bodies run on the EDT
     * and are intentionally lock-free apart from short synchronized appends of
     * numeric records; the watchdog runs on a separate daemon thread.
     */
    private final class EdtSampler {

        private final long edtThreadId;
        private final long thresholdMillis;
        private final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        private final List<double[]> gapSamplesByPhase = new ArrayList<>();
        private final List<double[]> latencySamplesByPhase = new ArrayList<>();
        private final List<StackCapture> captures = new ArrayList<>();
        private final Map<String, Integer> turboismFrameCounts = new HashMap<>();
        private volatile Phase phase = Phase.BASELINE;
        private volatile long lastEventMillis;
        private volatile long pendingTriggerMillis = -1L;
        private volatile String pendingTriggerReason = "";
        private volatile long lastCaptureMillis;
        private volatile int mouseEventCount;
        private final Object triggerLock = new Object();

        private ScheduledExecutorService heartbeat;
        private Thread watchdog;
        private boolean running;

        EdtSampler(final long edtThreadId, final long thresholdMillis) {
            this.edtThreadId = edtThreadId;
            this.thresholdMillis = thresholdMillis;
            for (Phase value : Phase.values()) {
                gapSamplesByPhase.add(new double[0]);
                latencySamplesByPhase.add(new double[0]);
            }
        }

        void setPhase(final Phase next) {
            phase = next;
        }

        /** Reset the event-gap baseline so the first interaction event's gap is real. */
        void resetEventGap() {
            lastEventMillis = System.currentTimeMillis();
        }

        int mouseEventCount() {
            return mouseEventCount;
        }

        void start() {
            running = true;
            final long thresholdMillis = this.thresholdMillis;
            lastEventMillis = System.currentTimeMillis();
            // The registration mask below already restricts the listener to mouse
            // events; the callback body runs on the EDT and must stay cheap.
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (!running) {
                    return;
                }
                final long now = System.currentTimeMillis();
                final long gap = now - lastEventMillis;
                lastEventMillis = now;
                final Phase current = phase;
                appendSample(gapSamplesByPhase, current, gap);
                mouseEventCount++;
                if (gap >= thresholdMillis) {
                    synchronized (triggerLock) {
                        pendingTriggerMillis = gap;
                        pendingTriggerReason = "EVENT_GAP";
                    }
                }
            }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);

            heartbeat = new ScheduledThreadPoolExecutor(1, runnable -> {
                final Thread thread = new Thread(runnable, "selection-lag-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeat.scheduleAtFixedRate(() -> {
                if (!running) {
                    return;
                }
                final long enqueue = System.currentTimeMillis();
                SwingUtilities.invokeLater(() -> {
                    final long latency = System.currentTimeMillis() - enqueue;
                    final Phase current = phase;
                    appendSample(latencySamplesByPhase, current, latency);
                    if (latency >= thresholdMillis) {
                        synchronized (triggerLock) {
                            pendingTriggerMillis = latency;
                            pendingTriggerReason = "HEARTBEAT";
                        }
                    }
                });
            }, HEARTBEAT_PERIOD_MILLIS, HEARTBEAT_PERIOD_MILLIS, TimeUnit.MILLISECONDS);

            watchdog = new Thread(() -> {
                while (running) {
                    final String reason;
                    final long millis;
                    synchronized (triggerLock) {
                        if (pendingTriggerMillis < 0) {
                            reason = null;
                            millis = -1;
                        } else {
                            reason = pendingTriggerReason;
                            millis = pendingTriggerMillis;
                            pendingTriggerMillis = -1;
                            pendingTriggerReason = "";
                        }
                    }
                    if (reason != null) {
                        final long now = System.currentTimeMillis();
                        if (now - lastCaptureMillis >= TRIGGER_COOLDOWN_MILLIS) {
                            lastCaptureMillis = now;
                            captureStack(reason, millis);
                        }
                    }
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "selection-lag-stack-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private void captureStack(final String reason, final long triggerMillis) {
            final String capturedPhase = phase.name();
            for (int shot = 0; shot < CAPTURE_SHOTS; shot++) {
                if (captures.size() >= CAPTURE_LIMIT) {
                    return;
                }
                final ThreadInfo info = bean.getThreadInfo(
                    new long[] {edtThreadId}, STACK_DEPTH)[0];
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
                        turboismFrameCounts.merge(line, 1, Integer::sum);
                    }
                }
                captures.add(new StackCapture(Instant.now().toString(), reason, triggerMillis,
                    capturedPhase, info.getThreadState().name(), frames, turboismFrames));
                try {
                    Thread.sleep(CAPTURE_SHOT_GAP_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void appendSample(final List<double[]> perPhase, final Phase phase,
                final long value) {
            final double[] samples = perPhase.get(phase.ordinal());
            final double[] grown = new double[samples.length + 1];
            System.arraycopy(samples, 0, grown, 0, samples.length);
            grown[samples.length] = value;
            perPhase.set(phase.ordinal(), grown);
        }

        void stop() {
            running = false;
            if (heartbeat != null) {
                heartbeat.shutdownNow();
            }
            if (watchdog != null) {
                watchdog.interrupt();
            }
        }

        void exportStats(final ProbeResult result) {
            for (Phase value : Phase.values()) {
                result.phases.put(value.name(), new PhaseStats(
                    gapSamplesByPhase.get(value.ordinal()).length,
                    percentiles(gapSamplesByPhase.get(value.ordinal())),
                    latencySamplesByPhase.get(value.ordinal()).length,
                    percentiles(latencySamplesByPhase.get(value.ordinal()))));
            }
            result.interactionMouseEvents =
                gapSamplesByPhase.get(Phase.INTERACTION.ordinal()).length;
            result.overThresholdCount = captures.size();
            result.stackCaptures = List.copyOf(captures);
            result.turboismFrameCounts = new HashMap<>(turboismFrameCounts);
        }

        private static long[] percentiles(final double[] samples) {
            if (samples.length == 0) {
                return new long[] {-1, -1, -1};
            }
            final double[] sorted = samples.clone();
            Arrays.sort(sorted);
            final long p50 = (long) sorted[(int) (sorted.length * 0.50)];
            final long p95 = (long) sorted[(int) Math.min(sorted.length - 1, sorted.length * 0.95)];
            final long max = (long) sorted[sorted.length - 1];
            return new long[] {p50, p95, max};
        }
    }

    // ------------------------------------------------------------------
    // Evidence records
    // ------------------------------------------------------------------

    private record StackCapture(String utc, String trigger, long triggerMillis, String phase,
            String edtState, List<String> frames, List<String> turboismFrames) {
    }

    private record PhaseStats(long eventCount, long[] eventGapMillis,
            long heartbeatCount, long[] heartbeatLatencyMillis) {
    }

    private record InteractionAttempt(int index, int x, int y, long robotMillis,
            List<String> selectedObjectIdsAfter, String activeArtMeshAfter,
            long selectionReadTimedOutMs, int mouseEventsTotal) {
    }

    private static final class ProbeResult {
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
        int interactionMouseEvents;
        int overThresholdCount;
        List<StackCapture> stackCaptures = List.of();
        Map<String, Integer> turboismFrameCounts = new HashMap<>();
        long durationMillis;
        String status = "BLOCKED";
        String statusReason = "probe did not finish";
    }

    // ------------------------------------------------------------------
    // Host thread helper
    // ------------------------------------------------------------------

    private <T> T onHostThread(final Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                "Cubism EDT did not accept the probe read within " + EDT_TIMEOUT_MILLIS + " ms.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
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
                .append("interactionMouseEvents=").append(result.interactionMouseEvents).append('\n')
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
        field(json, "interactionMouseEvents", result.interactionMouseEvents);
        field(json, "overThresholdEvents", result.overThresholdCount);

        json.append("  \"interactionAttempts\": [\n");
        for (int index = 0; index < result.interactionAttempts.size(); index++) {
            final InteractionAttempt attempt = result.interactionAttempts.get(index);
            json.append("    {\"index\": ").append(attempt.index())
                .append(", \"x\": ").append(attempt.x())
                .append(", \"y\": ").append(attempt.y())
                .append(", \"robotMillis\": ").append(attempt.robotMillis())
                .append(", \"mouseEventsTotal\": ").append(attempt.mouseEventsTotal());
            if (index + 1 < result.interactionAttempts.size()) {
                json.append("},\n");
            } else {
                json.append("}\n");
            }
        }
        json.append("  ],\n");

        json.append("  \"phases\": {\n");
        final List<String> phaseNames = new ArrayList<>(result.phases.keySet());
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
