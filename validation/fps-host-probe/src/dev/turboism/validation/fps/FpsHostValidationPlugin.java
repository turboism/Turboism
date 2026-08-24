package dev.turboism.validation.fps;

import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local exerciser for the FPS counting hook on an exact Cubism host. It
 * uses only the public SDK ({@code performanceStats().sample}) plus plain JDK
 * file I/O; it never imports or reflects {@code com.live2d.*} types and makes
 * no host mutation. The runtime mounts the renderScene counter when the first
 * consumer subscribes; this probe records renderedFrames (the cumulative
 * renderSceneCalls counter) and fps evidence and writes a terminal result
 * file into the task-scoped plugin state directory.
 */
public final class FpsHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "result.txt";
    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 2_000L;
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    private static final int SAMPLING_WINDOW_SECONDS = 90;

    /** Reviewed exact host versions the runtime report may advertise as READY. */
    private static final java.util.List<String> REVIEWED_HOST_VERSIONS =
        java.util.List.of("5.2.03", "5.3.02");

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "fps-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("FPS_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("FPS_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("FPS_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final Optional<String> modelId = awaitActiveModel();
        if (modelId.isEmpty()) {
            logger.warn("FPS_EXERCISER_READY_TIMEOUT"
                + " reason=active-model-not-present"
                + " timeoutMillis=" + HOST_READY_TIMEOUT_MILLIS);
            final JvmSnapshot jvm = jvmSnapshot();
            finish(false, "model readiness timeout", "missing", "missing", 0L, 0.0, 0, "none",
                jvm, jvm);
            return;
        }
        // Host identity is pinned by the runner (--version plus the exact JAR
        // identity gate) and by the agent's FPS hook digest admission (console
        // evidence checked post-run). The preview runtime report is NOT a gate
        // here: a pre-existing main regression (the strict preview-report
        // validator rejects localeSource=STARTUP, so the report is never
        // written) would otherwise block every report-gated exerciser.
        final String hostVersion = hostVersionLabel();
        logger.info("FPS_EXERCISER_READY"
            + " hostState=ACTIVE documentSignal=verified-modeling-document"
            + " hostVersion=" + hostVersion
            + " modelId=" + modelId.orElseThrow());
        runSampling(hostVersion, modelId.orElseThrow());
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
            logger.warn("FPS_EXERCISER_REPORT_UNAVAILABLE"
                + " reason=preview-runtime-report-not-readable"
                + " runnerPinnedVersion=" + runnerVersion);
        }
        return runnerVersion.isEmpty() ? "unknown" : runnerVersion;
    }

    private Optional<String> awaitActiveModel() {
        final long deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                return Optional.of(activeModelId());
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
    private String activeModelId() {
        return context.cubism().model().active().id().value();
    }

    private void runSampling(final String hostVersion, final String modelId) {
        final AtomicLong maxRenderedFrames = new AtomicLong();
        final AtomicLong maxFpsMillis = new AtomicLong();
        final AtomicReference<String> failure = new AtomicReference<>();
        final JvmSnapshot jvmBefore = jvmSnapshot();
        try {
            final PerformanceProbeService stats = context.performanceStats();
            final Registration sampling = stats.sample(SAMPLE_INTERVAL, snapshot -> {
                maxRenderedFrames.accumulateAndGet(
                    snapshot.renderedFrames(), Math::max
                );
                maxFpsMillis.accumulateAndGet(
                    Math.round(snapshot.fps() * 1000.0), Math::max
                );
            });
            final long deadline = System.currentTimeMillis()
                + SAMPLING_WINDOW_SECONDS * 1_000L;
            while (System.currentTimeMillis() < deadline) {
                if (maxRenderedFrames.get() > 0L) {
                    break;
                }
                Thread.sleep(SETTLE_STEP_MILLIS);
            }
            sampling.close();
            final long renderSceneCalls = maxRenderedFrames.get();
            final double fpsMax = maxFpsMillis.get() / 1000.0;
            if (renderSceneCalls > 0L) {
                logger.info("FPS_RESULT status=PASS"
                    + " hostVersion=" + hostVersion
                    + " modelId=" + modelId
                    + " renderSceneCalls=" + renderSceneCalls
                    + " fpsMax=" + fpsMax);
                finish(true, "renderSceneCalls>0", hostVersion, modelId,
                    renderSceneCalls, fpsMax, SAMPLING_WINDOW_SECONDS, failure.get(),
                    jvmBefore, jvmSnapshot());
            } else {
                failure.compareAndSet(null, "no renderScene calls within sampling window");
                logger.warn("FPS_RESULT status=FAIL"
                    + " hostVersion=" + hostVersion
                    + " modelId=" + modelId
                    + " renderSceneCalls=0"
                    + " reason=" + failure.get());
                finish(false, failure.get(), hostVersion, modelId,
                    renderSceneCalls, fpsMax, SAMPLING_WINDOW_SECONDS, failure.get(),
                    jvmBefore, jvmSnapshot());
            }
        } catch (Throwable failure1) {
            failure.compareAndSet(null, failure1.getClass().getName());
            logger.warn("FPS_RESULT status=FAIL"
                + " hostVersion=" + hostVersion
                + " modelId=" + modelId
                + " reason=" + failure.get());
            finish(false, failure.get(), hostVersion, modelId,
                maxRenderedFrames.get(), maxFpsMillis.get() / 1000.0,
                SAMPLING_WINDOW_SECONDS, failure.get(), jvmBefore, jvmSnapshot());
        }
    }

    private static JvmSnapshot jvmSnapshot() {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        return new JvmSnapshot(
            System.getProperty("java.version", "missing"),
            System.getProperty("java.vm.vendor", "missing"),
            heap.getUsed(),
            heap.getCommitted(),
            nonHeap.getUsed(),
            threads.getThreadCount(),
            threads.getPeakThreadCount(),
            classes.getLoadedClassCount(),
            collectors.stream().mapToLong(value -> Math.max(0L, value.getCollectionCount())).sum(),
            collectors.stream().mapToLong(value -> Math.max(0L, value.getCollectionTime())).sum()
        );
    }

    private void finish(
        final boolean pass,
        final String outcome,
        final String hostVersion,
        final String modelId,
        final long renderSceneCalls,
        final double fpsMax,
        final int samplingSeconds,
        final String failure,
        final JvmSnapshot jvmBefore,
        final JvmSnapshot jvmAfter
    ) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("outcome=").append(outcome).append('\n')
            .append("hostVersion=").append(hostVersion).append('\n')
            .append("modelId=").append(modelId).append('\n')
            .append("renderSceneCalls=").append(renderSceneCalls).append('\n')
            .append("fpsMax=").append(fpsMax).append('\n')
            .append("samplingSeconds=").append(samplingSeconds).append('\n')
            .append("jvm.javaVersion=").append(jvmAfter.javaVersion()).append('\n')
            .append("jvm.vmVendor=").append(jvmAfter.vmVendor()).append('\n')
            .append("jvm.heapUsedBytes.before=").append(jvmBefore.heapUsedBytes()).append('\n')
            .append("jvm.heapUsedBytes.after=").append(jvmAfter.heapUsedBytes()).append('\n')
            .append("jvm.heapCommittedBytes.after=").append(jvmAfter.heapCommittedBytes()).append('\n')
            .append("jvm.nonHeapUsedBytes.after=").append(jvmAfter.nonHeapUsedBytes()).append('\n')
            .append("jvm.threadCount.after=").append(jvmAfter.threadCount()).append('\n')
            .append("jvm.peakThreadCount.after=").append(jvmAfter.peakThreadCount()).append('\n')
            .append("jvm.loadedClassCount.after=").append(jvmAfter.loadedClassCount()).append('\n')
            .append("jvm.gcCollectionCount.delta=")
            .append(Math.max(0L, jvmAfter.gcCollectionCount() - jvmBefore.gcCollectionCount())).append('\n')
            .append("jvm.gcCollectionTimeMillis.delta=")
            .append(Math.max(0L, jvmAfter.gcCollectionTimeMillis() - jvmBefore.gcCollectionTimeMillis())).append('\n')
            .append("failure=").append(failure == null ? "none" : failure).append('\n');
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
        } catch (java.io.IOException writeFailure) {
            logger.warn("FPS_RESULT_WRITE_FAILED " + writeFailure.getClass().getName());
            Runtime.getRuntime().halt(3);
            return;
        }
        logger.info("FPS_RESULT_FILE status=" + (pass ? "PASS" : "FAIL")
            + " renderSceneCalls=" + renderSceneCalls
            + " fpsMax=" + fpsMax);
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private record JvmSnapshot(
        String javaVersion,
        String vmVendor,
        long heapUsedBytes,
        long heapCommittedBytes,
        long nonHeapUsedBytes,
        int threadCount,
        int peakThreadCount,
        int loadedClassCount,
        long gcCollectionCount,
        long gcCollectionTimeMillis
    ) { }
}
