package dev.turboism.validation.modelupdate;

import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Task-local exerciser for the model-update unchanged-frame skip on an exact
 * Cubism host. It uses only the public SDK (active-model gate, performance
 * stats sampling) plus plain JDK property/file reads; it never imports or
 * reflects {@code com.live2d.*} types itself. The separate opt-in native interaction
 * workload uses exact-host public state access and mouse input on the task copy,
 * including authoring drag and native Undo/Redo validation. The bridge
 * counters are read through the {@code turboism.model-update-skip.stats}
 * system-property slot the installer occupies; the probe-mode mismatch report
 * is read from the JSON-lines path named by
 * {@code turboism.model-update-skip.probe.result}. A terminal result file is
 * written into the task-scoped plugin state directory.
 */
public final class ModelUpdateSkipHostProbePlugin implements TurboismPlugin {

    private static final String RESULT = "result.txt";
    private static final String STATS_SLOT = "turboism.model-update-skip.stats";
    private static final String CALLBACK_SLOT = "turboism.model-update-skip.callback";
    private static final String AFTER_SLOT = "turboism.model-update-skip.after";
    private static final String ENABLE_PROPERTY = "turboism.optimization.modelUpdateSkip";
    private static final String PROBE_PROPERTY = "turboism.model-update-skip.probe";
    private static final String PROBE_RESULT_PROPERTY = "turboism.model-update-skip.probe.result";

    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 2_000L;
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    /** Repaint activity needed before the counter assertions are evaluated. */
    private static final long MIN_CALLS = 30L;
    private static final long WARMUP_MILLIS = 5_000L;
    private static final long SAMPLE_WINDOW_MILLIS = 20_000L;
    /** Extra observation time after the live-disable toggle. */
    private static final long DISABLE_OBSERVE_MILLIS = 6_000L;

    private static final java.util.List<String> REVIEWED_HOST_VERSIONS =
        java.util.List.of("5.2.03", "5.3.02", "5.3.03");

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private Map<String, Long> windowStartStats, windowEndStats;
    private long windowElapsedNanos;
    private long windowRenderedFrames;
    private long sampleCount;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "model-update-skip-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("MODEL_UPDATE_SKIP_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("MODEL_UPDATE_SKIP_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("MODEL_UPDATE_SKIP_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final Optional<String> modelId = awaitActiveModel();
        if (modelId.isEmpty()) {
            logger.warn("MODEL_UPDATE_SKIP_EXERCISER_READY_TIMEOUT"
                + " reason=active-model-not-present"
                + " timeoutMillis=" + HOST_READY_TIMEOUT_MILLIS);
            finish(false, "model readiness timeout", mode(), hostVersionLabel(),
                "missing", null, null, 0L, List.of(), "none");
            return;
        }
        final String hostVersion = hostVersionLabel();
        final String mode = mode();
        logger.info("MODEL_UPDATE_SKIP_EXERCISER_READY"
            + " hostState=ACTIVE documentSignal=verified-modeling-document"
            + " hostVersion=" + hostVersion
            + " modelId=" + modelId.orElseThrow()
            + " mode=" + mode);
        if ("wheel".equals(System.getProperty("turboism.validation.modelUpdateWorkload"))) {
            runWheelBenchmark(hostVersion, modelId.orElseThrow(), mode);
        } else {
            runSampling(hostVersion, modelId.orElseThrow(), mode);
        }
    }

    private void runWheelBenchmark(final String hostVersion, final String modelId, final String mode) {
        try {
            Thread.sleep(WARMUP_MILLIS);
            final String interaction = System.getProperty("turboism.validation.nativeInteraction", "");
            if (interaction.isEmpty()) {
                new CanvasWheelWorkload(System.getProperty("turboism.validation.fixtureName"), stateDir)
                    .run("probe".equals(mode));
            } else {
                if ("probe".equals(mode)) throw new IllegalArgumentException("native interaction does not run model digest probes");
                new NativeInteractionWorkload(System.getProperty("turboism.validation.fixtureName"), stateDir, interaction).run();
            }
            final Map<String, Long> counters = statsSnapshot();
            final boolean pass = counters != null && number(counters, "failures") == 0L
                && number(counters, "probeMismatch") == 0L;
            finish(pass, pass ? "camera workload completed; inspect wheel-benchmark.txt"
                : "camera workload counter failure", mode, hostVersion, modelId, counters,
                null, 0L, List.of(), "none");
        } catch (Throwable failure) {
            logger.warn("MODEL_UPDATE_SKIP_WHEEL_FAILURE " + failure);
            finish(false, "camera workload failed: " + failure, mode, hostVersion, modelId,
                statsSnapshot(), null, 0L, List.of(), failure.toString());
        }
    }

    private String mode() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return "off";
        return Boolean.parseBoolean(System.getProperty(PROBE_PROPERTY, "false")) ? "probe" : "on";
    }

    /**
     * Host version label: the reviewed report version when the report is
     * written and MATCHED, otherwise the runner-pinned validation version
     * (the same value the exact JAR identity gate was checked against).
     */
    private String hostVersionLabel() {
        final String runnerVersion = System.getProperty("turboism.validation.hostVersion", "");
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            if (json.contains("\"identityState\":\"MATCHED\"")) {
                for (String reviewed : REVIEWED_HOST_VERSIONS) {
                    if (json.contains("\"version\":\"" + reviewed + "\"")) {
                        return reviewed;
                    }
                }
            }
        } catch (java.io.IOException unavailable) {
            logger.warn("MODEL_UPDATE_SKIP_EXERCISER_REPORT_UNAVAILABLE"
                + " reason=preview-runtime-report-not-readable"
                + " runnerPinnedVersion=" + runnerVersion);
        }
        return runnerVersion.isEmpty() ? "unknown" : runnerVersion;
    }

    private Optional<String> awaitActiveModel() {
        final long deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                return Optional.of(context.cubism().model().active().id().value());
            } catch (RuntimeException unavailable) {
                // host or document not ready yet; keep polling
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> statsSnapshot() {
        final Object slot = System.getProperties().get(STATS_SLOT);
        if (!(slot instanceof Supplier<?> supplier)) return null;
        final Object snapshot = supplier.get();
        if (!(snapshot instanceof Map<?, ?> map)) return null;
        final java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                out.put(key, value.longValue());
            }
        }
        return out;
    }

    private static long number(final Map<String, Long> stats, final String key) {
        if (stats == null) return -1L;
        final Long value = stats.get(key);
        return value == null ? -1L : value;
    }

    private void runSampling(final String hostVersion, final String modelId, final String mode) {
        final AtomicLong maxRenderedFrames = new AtomicLong();
        final AtomicLong firstRenderedFrames = new AtomicLong(-1L);
        final AtomicLong samples = new AtomicLong();
        final java.util.List<Double> fpsSeries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.concurrent.atomic.AtomicReference<String> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        Map<String, Long> last = null;
        Long disableProbeResult = null;
        try {
            // Identical warm-up and observation lengths for OFF, PROBE and ON.
            // Work-count-dependent termination would compare different workloads.
            Thread.sleep(WARMUP_MILLIS);
            if (Boolean.getBoolean("turboism.validation.modelUpdateUiInventory")) {
                inventoryFixtureWindow();
            }
            final PerformanceProbeService stats = context.performanceStats();
            windowStartStats = statsSnapshot();
            final long started = System.nanoTime();
            final Registration sampling = stats.sample(SAMPLE_INTERVAL, snapshot -> {
                firstRenderedFrames.compareAndSet(-1L, snapshot.renderedFrames());
                maxRenderedFrames.accumulateAndGet(snapshot.renderedFrames(), Math::max);
                samples.incrementAndGet();
                fpsSeries.add(snapshot.fps());
            });
            try {
                final long windowNanos = Duration.ofMillis(SAMPLE_WINDOW_MILLIS).toNanos();
                while (System.nanoTime() - started < windowNanos) {
                    Thread.sleep(Math.min(250L, SAMPLE_WINDOW_MILLIS));
                }
                last = statsSnapshot();
                windowEndStats = last;
                windowElapsedNanos = System.nanoTime() - started;
            } finally {
                sampling.close();
            }
            sampleCount = samples.get();
            windowRenderedFrames = firstRenderedFrames.get() < 0L ? 0L
                : Math.max(0L, maxRenderedFrames.get() - firstRenderedFrames.get());
            // Live-disable check for the enabled modes: while the switch is
            // off the skip callback returns before counting, so `skipped`
            // and `calls` freeze while `full` keeps counting the native
            // updates the repaints still drive.
            if ("on".equals(mode) && last != null && number(last, "calls") > 0L) {
                final long skippedBefore = number(last, "skipped");
                final long fullBefore = number(last, "full");
                System.setProperty(ENABLE_PROPERTY, "false");
                Thread.sleep(DISABLE_OBSERVE_MILLIS);
                System.setProperty(ENABLE_PROPERTY, "true");
                final Map<String, Long> after = statsSnapshot();
                if (after != null
                    && number(after, "skipped") == skippedBefore
                    && number(after, "full") > fullBefore) {
                    disableProbeResult = 1L;
                } else {
                    disableProbeResult = 0L;
                }
                last = after;
            }
            evaluate(hostVersion, modelId, mode, last,
                maxRenderedFrames.get(), disableProbeResult, fpsSeries, failure);
        } catch (Throwable failure1) {
            failure.compareAndSet(null, failure1.getClass().getName());
            logger.warn("MODEL_UPDATE_SKIP_RESULT status=FAIL"
                + " hostVersion=" + hostVersion + " modelId=" + modelId
                + " mode=" + mode + " reason=" + failure.get());
            finish(false, failure.get(), mode, hostVersion, modelId, last,
                disableProbeResult, maxRenderedFrames.get(), fpsSeries, failure.get());
        }
    }

    /** Read-only, bounded Swing inventory of this task's fixture window on the EDT. */
    private void inventoryFixtureWindow() throws Exception {
        final String fixture = System.getProperty("turboism.validation.fixtureName", "");
        if (fixture.isBlank()) throw new IllegalStateException("task fixture name absent");
        final StringBuilder inventory = new StringBuilder();
        final java.util.concurrent.FutureTask<Void> scan = new java.util.concurrent.FutureTask<>(() -> {
            int matched = 0;
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof java.awt.Frame frame && frame.isShowing()
                    && frame.getTitle().contains(fixture)) {
                    matched++;
                    inventoryComponent(frame, "window", 0, new int[]{0}, inventory);
                }
            }
            if (matched != 1) throw new IllegalStateException("fixture window matches=" + matched);
            return null;
        });
        java.awt.EventQueue.invokeLater(scan);
        scan.get(10L, java.util.concurrent.TimeUnit.SECONDS);
        Files.writeString(stateDir.resolve("ui-inventory.txt"), inventory);
    }

    private static void inventoryComponent(final java.awt.Component component, final String path,
                                           final int depth, final int[] count,
                                           final StringBuilder out) {
        if (depth > 32 || ++count[0] > 4096) throw new IllegalStateException("UI scan bound exceeded");
        if (!component.isShowing()) return;
        out.append(path).append(' ').append(component.getClass().getName())
            .append(" size=").append(component.getWidth()).append('x').append(component.getHeight())
            .append(" wheelListeners=").append(component.getMouseWheelListeners().length);
        for (java.awt.event.MouseWheelListener listener : component.getMouseWheelListeners()) {
            out.append(" listener=").append(listener.getClass().getName());
        }
        if (component instanceof javax.swing.JLabel label) {
            out.append(" text=").append(label.getText());
        } else if (component instanceof javax.swing.JComboBox<?> combo) {
            out.append(" selected=").append(combo.getSelectedItem());
        }
        out.append('\n');
        if (component instanceof java.awt.Container container) {
            final java.awt.Component[] children = container.getComponents();
            for (int i = 0; i < children.length; i++) {
                inventoryComponent(children[i], path + "/" + i, depth + 1, count, out);
            }
        }
    }

    private void evaluate(
        final String hostVersion,
        final String modelId,
        final String mode,
        final Map<String, Long> stats,
        final long renderSceneCalls,
        final Long disableProbeResult,
        final List<Double> fpsSeries,
        final java.util.concurrent.atomic.AtomicReference<String> failure
    ) {
        String outcome = null;
        boolean pass;
        if ("off".equals(mode)) {
            final Properties properties = System.getProperties();
            pass = stats == null
                && properties.get(CALLBACK_SLOT) == null
                && properties.get(AFTER_SLOT) == null
                && windowRenderedFrames > 0L;
            outcome = pass ? "disabled hook absent with repaint activity"
                : "disabled hook present or no repaint activity";
        } else if (stats == null) {
            pass = false;
            outcome = "stats slot absent; hook not installed";
        } else if (number(windowEndStats, "calls")
            - Math.max(0L, number(windowStartStats, "calls")) < MIN_CALLS) {
            pass = false;
            outcome = "insufficient model-update entries in fixed sampling window";
        } else {
            pass = number(stats, "failures") == 0L
                && number(stats, "probeMismatch") == 0L
                && number(stats, "full") > 0L;
            outcome = pass ? "counters consistent" : "counter assertion failed";
            if (pass && "probe".equals(mode)) {
                pass = number(stats, "skipped") == 0L
                    && number(stats, "digestNanos") > 0L
                    && probeReportClean();
                outcome = pass ? "probe digests ran with zero mismatches" : "probe assertion failed";
            }
            if (pass && "on".equals(mode)) {
                pass = number(stats, "skipped") > 0L;
                outcome = pass ? "unchanged frames skipped" : "no frames were skipped";
            }
            if (pass && "on".equals(mode) && disableProbeResult != null) {
                pass = disableProbeResult == 1L;
                outcome = pass ? outcome : "live disable left skip counter advancing";
            }
        }
        logger.info("MODEL_UPDATE_SKIP_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " hostVersion=" + hostVersion + " modelId=" + modelId + " mode=" + mode
            + " outcome=" + outcome + statsLine(stats));
        finish(pass, outcome, mode, hostVersion, modelId, stats,
            disableProbeResult, renderSceneCalls, fpsSeries, failure.get());
    }

    /**
     * The probe result file is only ever written on a digest mismatch, so a
     * clean probe leg has no file at all or an empty one.
     */
    private boolean probeReportClean() {
        final String path = System.getProperty(PROBE_RESULT_PROPERTY);
        if (path == null || path.isBlank()) return false;
        try {
            final Path report = Path.of(path);
            return !Files.exists(report) || Files.size(report) == 0L;
        } catch (java.io.IOException unreadable) {
            return false;
        }
    }

    /**
     * Jank summary over the per-interval fps series: p05/min describe the
     * dip depth; stallIntervals counts intervals below half the median.
     */
    private static JankSummary summarizeJank(final List<Double> series) {
        final List<Double> sorted;
        synchronized (series) {
            sorted = new java.util.ArrayList<>(series);
        }
        if (sorted.isEmpty()) {
            return new JankSummary(0.0, 0.0, 0.0, 0, 0);
        }
        sorted.sort(null);
        final double min = sorted.get(0);
        final double p05 = sorted.get(Math.min(sorted.size() - 1,
            (int) Math.floor(sorted.size() * 0.05)));
        final double median = sorted.get(sorted.size() / 2);
        int stalls = 0;
        int zeros = 0;
        for (double fps : sorted) {
            if (fps <= 0.0) zeros++;
            if (fps < median * 0.5) stalls++;
        }
        return new JankSummary(min, p05, median, stalls, zeros);
    }

    private record JankSummary(
        double min,
        double p05,
        double median,
        int stallIntervals,
        int zeroFpsIntervals
    ) { }

    private static String seriesString(final List<Double> series) {
        final List<Double> copy;
        synchronized (series) {
            copy = new java.util.ArrayList<>(series);
        }
        final StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) out.append(',');
            out.append(String.format(java.util.Locale.ROOT, "%.2f", copy.get(i)));
        }
        return out.append(']').toString();
    }

    private static String statsLine(final Map<String, Long> stats) {
        if (stats == null) return " stats=absent";
        final StringBuilder line = new StringBuilder(128);
        for (String key : new java.util.TreeSet<>(stats.keySet())) {
            line.append(' ').append(key).append('=').append(number(stats, key));
        }
        return line.toString();
    }

    private void finish(
        final boolean pass,
        final String outcome,
        final String mode,
        final String hostVersion,
        final String modelId,
        final Map<String, Long> stats,
        final Long disableProbeResult,
        final long renderSceneCalls,
        final List<Double> fpsSeries,
        final String failure
    ) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("outcome=").append(outcome).append('\n')
            .append("mode=").append(mode).append('\n')
            .append("hostVersion=").append(hostVersion).append('\n')
            .append("modelId=").append(modelId).append('\n')
            .append("renderSceneCalls=").append(renderSceneCalls).append('\n');
        for (String key : List.of("calls", "skipped", "full", "probeMismatch", "failures",
            "predicateNanos", "predicateMaxNanos", "digestNanos")) {
            result.append("modelUpdate.").append(key).append('=')
                .append(number(stats, key)).append('\n');
        }
        if (stats != null) {
            for (String key : new java.util.TreeSet<>(stats.keySet())) {
                if (List.of("calls", "skipped", "full", "probeMismatch", "failures",
                    "predicateNanos", "predicateMaxNanos", "digestNanos").contains(key)) continue;
                result.append("modelUpdate.").append(key).append('=')
                    .append(number(stats, key)).append('\n');
            }
        }
        result.append("workload=").append("wheel".equals(
            System.getProperty("turboism.validation.modelUpdateWorkload"))
                ? "awt-wheel-native-repaint-barrier" : "window-resize-smoke").append('\n')
            .append("performanceAccepted=false\n")
            .append("window.elapsedNanos=").append(windowElapsedNanos).append('\n')
            .append("window.requestedMillis=").append(SAMPLE_WINDOW_MILLIS).append('\n')
            .append("window.renderedFramesBetweenSamples=").append(windowRenderedFrames).append('\n')
            .append("window.sampleCount=").append(sampleCount).append('\n');
        if (windowStartStats != null && windowEndStats != null) {
            for (String key : new java.util.TreeSet<>(windowEndStats.keySet())) {
                if (key.equals("active") || key.equals("parameterCount") || key.endsWith("MaxNanos")) continue;
                result.append("window.modelUpdate.").append(key).append('=')
                    .append(number(windowEndStats, key) - number(windowStartStats, key)).append('\n');
            }
        }
        if (disableProbeResult != null) {
            result.append("modelUpdate.liveDisableObserved=").append(disableProbeResult == 1L).append('\n');
        }
        final JankSummary jank = summarizeJank(fpsSeries);
        result.append("jank.fpsMin=").append(jank.min()).append('\n')
            .append("jank.fpsP05=").append(jank.p05()).append('\n')
            .append("jank.fpsMedian=").append(jank.median()).append('\n')
            .append("jank.stallIntervals=").append(jank.stallIntervals()).append('\n')
            .append("jank.zeroFpsIntervals=").append(jank.zeroFpsIntervals()).append('\n')
            .append("jank.fpsSeries=").append(seriesString(fpsSeries)).append('\n');
        result.append("probeResultPath=")
            .append(System.getProperty(PROBE_RESULT_PROPERTY, "unset")).append('\n')
            .append("failure=").append(failure == null ? "none" : failure).append('\n');
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
        } catch (java.io.IOException writeFailure) {
            logger.warn("MODEL_UPDATE_SKIP_RESULT_WRITE_FAILED " + writeFailure.getClass().getName());
            Runtime.getRuntime().halt(3);
            return;
        }
        logger.info("MODEL_UPDATE_SKIP_RESULT_FILE status=" + (pass ? "PASS" : "FAIL")
            + " mode=" + mode + " outcome=" + outcome);
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }
}
