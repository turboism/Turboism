package dev.turboism.validation.incrementalupdate;

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
 * Task-local exerciser for the per-object incremental model update (slice B)
 * on an exact Cubism host. It uses only the public SDK (active-model gate,
 * performance stats sampling) plus plain JDK property/file reads; it never
 * imports or reflects {@code com.live2d.*} types and makes no host mutation.
 * The bridge counters are read through the {@code turboism.incremental-update.stats}
 * system-property slot the installer occupies; the probe-mode mismatch report
 * is read from the JSON-lines path named by
 * {@code turboism.incremental-update.probe.result}. A terminal result file is
 * written into the task-scoped plugin state directory.
 */
public final class IncrementalUpdateHostProbePlugin implements TurboismPlugin {

    private static final String RESULT = "result.txt";
    private static final String STATS_SLOT = "turboism.incremental-update.stats";
    private static final String[] CALLBACK_SLOTS = {
        "turboism.incremental-update.begin",
        "turboism.incremental-update.end",
        "turboism.incremental-update.mark",
        "turboism.incremental-update.deform",
        "turboism.incremental-update.form",
        "turboism.incremental-update.stats"};
    private static final String ENABLE_PROPERTY = "turboism.optimization.incrementalUpdate";
    private static final String PROBE_PROPERTY = "turboism.incremental-update.probe";
    private static final String PROBE_RESULT_PROPERTY = "turboism.incremental-update.probe.result";

    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 2_000L;
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    /** Update epochs needed before the counter assertions are evaluated. */
    private static final long MIN_EPOCHS = 30L;
    private static final long SAMPLE_WINDOW_MILLIS = 180_000L;
    /** Extra observation time after the live-disable toggle. */
    private static final long DISABLE_OBSERVE_MILLIS = 6_000L;

    private static final java.util.List<String> REVIEWED_HOST_VERSIONS =
        java.util.List.of("5.2.03", "5.3.02", "5.3.03");

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "incremental-update-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("INCREMENTAL_UPDATE_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("INCREMENTAL_UPDATE_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("INCREMENTAL_UPDATE_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final Optional<String> modelId = awaitActiveModel();
        if (modelId.isEmpty()) {
            logger.warn("INCREMENTAL_UPDATE_EXERCISER_READY_TIMEOUT"
                + " reason=active-model-not-present"
                + " timeoutMillis=" + HOST_READY_TIMEOUT_MILLIS);
            finish(false, "model readiness timeout", mode(), hostVersionLabel(),
                "missing", null, null, 0L, List.of(), "none");
            return;
        }
        final String hostVersion = hostVersionLabel();
        final String mode = mode();
        logger.info("INCREMENTAL_UPDATE_EXERCISER_READY"
            + " hostState=ACTIVE documentSignal=verified-modeling-document"
            + " hostVersion=" + hostVersion
            + " modelId=" + modelId.orElseThrow()
            + " mode=" + mode);
        runSampling(hostVersion, modelId.orElseThrow(), mode);
    }

    private String mode() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return "off";
        return Boolean.parseBoolean(System.getProperty(PROBE_PROPERTY, "false")) ? "probe" : "on";
    }

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
            logger.warn("INCREMENTAL_UPDATE_EXERCISER_REPORT_UNAVAILABLE"
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
        final java.util.List<Double> fpsSeries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.concurrent.atomic.AtomicReference<String> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        Map<String, Long> last = null;
        Long disableProbeResult = null;
        try {
            final PerformanceProbeService stats = context.performanceStats();
            final Registration sampling = stats.sample(SAMPLE_INTERVAL, snapshot -> {
                maxRenderedFrames.accumulateAndGet(snapshot.renderedFrames(), Math::max);
                fpsSeries.add(snapshot.fps());
            });
            final long deadline = System.currentTimeMillis() + SAMPLE_WINDOW_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                last = statsSnapshot();
                if (last != null && number(last, "epochs") >= MIN_EPOCHS) break;
                Thread.sleep(SETTLE_STEP_MILLIS);
            }
            // Live-disable check for the enabled modes: with the switch off the
            // bridge forces full epochs, so narrowed counters freeze while the
            // forced-full counters keep advancing on the same repaint stream.
            if ("on".equals(mode) && last != null && number(last, "epochs") > 0L) {
                final long skippedBefore = number(last, "meshSkipped");
                final long deformedBefore = number(last, "meshDeformed");
                System.setProperty(ENABLE_PROPERTY, "false");
                Thread.sleep(DISABLE_OBSERVE_MILLIS);
                System.setProperty(ENABLE_PROPERTY, "true");
                final Map<String, Long> after = statsSnapshot();
                if (after != null
                    && number(after, "meshSkipped") == skippedBefore
                    && number(after, "meshDeformed") > deformedBefore) {
                    disableProbeResult = 1L;
                } else {
                    disableProbeResult = 0L;
                }
                last = after;
            }
            sampling.close();
            evaluate(hostVersion, modelId, mode, last,
                maxRenderedFrames.get(), disableProbeResult, fpsSeries, failure);
        } catch (Throwable failure1) {
            failure.compareAndSet(null, failure1.getClass().getName());
            logger.warn("INCREMENTAL_UPDATE_RESULT status=FAIL"
                + " hostVersion=" + hostVersion + " modelId=" + modelId
                + " mode=" + mode + " reason=" + failure.get());
            finish(false, failure.get(), mode, hostVersion, modelId, last,
                disableProbeResult, maxRenderedFrames.get(), fpsSeries, failure.get());
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
            boolean slotsAbsent = stats == null;
            for (String slot : CALLBACK_SLOTS) {
                slotsAbsent = slotsAbsent && properties.get(slot) == null;
            }
            pass = slotsAbsent;
            outcome = pass ? "no hook slots installed while disabled" : "disabled run installed hook slots";
        } else if (stats == null) {
            pass = false;
            outcome = "stats slot absent; hook not installed";
        } else if (number(stats, "epochs") <= 0L) {
            pass = false;
            outcome = "no update epochs observed within sampling window";
        } else {
            pass = number(stats, "failures") == 0L
                && number(stats, "probeMismatch") == 0L
                && number(stats, "markCalls") > 0L;
            outcome = pass ? "counters consistent" : "counter assertion failed";
            if (pass && "probe".equals(mode)) {
                pass = number(stats, "probePairs") > 0L
                    && number(stats, "digestNanos") > 0L
                    && probeReportClean();
                outcome = pass ? "probe pairs compared with zero mismatches" : "probe assertion failed";
            }
            if (pass && "on".equals(mode)) {
                pass = number(stats, "markedClean") > 0L
                    || number(stats, "meshSkipped") > 0L;
                outcome = pass ? "narrowed updates observed" : "no object was narrowed";
            }
            if (pass && "on".equals(mode) && disableProbeResult != null) {
                pass = disableProbeResult == 1L;
                outcome = pass ? outcome : "live disable left narrowed counters advancing";
            }
        }
        logger.info("INCREMENTAL_UPDATE_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " hostVersion=" + hostVersion + " modelId=" + modelId + " mode=" + mode
            + " outcome=" + outcome + statsLine(stats));
        finish(pass, outcome, mode, hostVersion, modelId, stats,
            disableProbeResult, renderSceneCalls, fpsSeries, failure.get());
    }

    /**
     * The probe result file is only ever written on a digest mismatch or a
     * digest failure, so a clean probe leg has no file at all or an empty one.
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
        final StringBuilder line = new StringBuilder(192);
        for (String key : List.of("epochs", "markCalls", "markedDirty", "markedClean",
            "descendantsMarked", "meshDeformed", "meshSkipped", "formsRecorded",
            "probePairs", "probeSkippedPairs", "probeMismatch", "failures", "digestNanos")) {
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
        for (String key : List.of("epochs", "markCalls", "markedDirty", "markedClean",
            "descendantsMarked", "meshDeformed", "meshSkipped", "formsRecorded",
            "probePairs", "probeSkippedPairs", "probeMismatch", "failures", "digestNanos")) {
            result.append("incremental.").append(key).append('=')
                .append(number(stats, key)).append('\n');
        }
        if (disableProbeResult != null) {
            result.append("incremental.liveDisableObserved=").append(disableProbeResult == 1L).append('\n');
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
            logger.warn("INCREMENTAL_UPDATE_RESULT_WRITE_FAILED " + writeFailure.getClass().getName());
            Runtime.getRuntime().halt(3);
            return;
        }
        logger.info("INCREMENTAL_UPDATE_RESULT_FILE status=" + (pass ? "PASS" : "FAIL")
            + " mode=" + mode + " outcome=" + outcome);
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }
}
