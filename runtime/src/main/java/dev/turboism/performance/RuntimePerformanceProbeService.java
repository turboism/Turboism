package dev.turboism.performance;

import com.sun.management.OperatingSystemMXBean;

import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHookRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.panel.ChartDataRegistry;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runtime implementation of {@link PerformanceProbeService}: composes process
 * CPU (JDK {@link OperatingSystemMXBean}), JVM heap/non-heap memory
 * ({@link MemoryMXBean}), and FPS derived from the agent's renderScene
 * counter into snapshots, and runs the periodic sampling loop on a background
 * daemon thread. The renderScene counter hook is mounted while at least one
 * sampling registration is active and unmounted (bytecode restored and
 * verified) when the last registration closes.
 *
 * <p>While sampling, rolling series values are published to
 * {@link ChartDataRegistry} under the canonical chart ids
 * ({@link #CHART_CPU}, {@link #CHART_FPS}, {@link #CHART_HEAP},
 * {@link #CHART_NONHEAP}, {@link #CHART_FRAMES}, {@link #CHART_GC}) so
 * embedded-panel {@code PanelView.Chart} components render the same data as
 * sampling consumers. GC totals come from the JVM garbage-collector MXBeans
 * as cumulative counters; the sampling tick diffs them against the previous
 * tick baseline and publishes the per-window pause (ms) as the GC Pause
 * series. Disk I/O stays unbound this phase.
 */
public final class RuntimePerformanceProbeService implements PerformanceProbeService {

    public static final String CHART_CPU = "cpu";
    public static final String CHART_FPS = "fps";
    public static final String CHART_HEAP = "heap";
    public static final String CHART_NONHEAP = "nonheap";
    public static final String CHART_FRAMES = "frames";
    public static final String CHART_GC = "gc";

    public static final String SERIES_CPU = "CPU %";
    public static final String SERIES_FPS = "FPS";
    public static final String SERIES_HEAP = "JVM Heap";
    public static final String SERIES_NONHEAP = "JVM Non-Heap";
    public static final String SERIES_FRAMES = "Rendered Frames";
    public static final String SERIES_GC_PAUSE = "GC Pause";

    /** Rolling window capacity; the displayed window is the last maxPoints (>= 120 at 1s). */
    private static final int BUFFER_CAPACITY = 240;

    /** Consecutive invalid getProcessCpuLoad results before latching the time-delta fallback. */
    private static final int INVALID_CPU_LOAD_LATCH_THRESHOLD = 2;

    private final String pluginId;
    private final PermissionChecker permissionChecker;
    private final Clock clock;
    private final Object lifecycle = new Object();
    private final List<Consumer<PerformanceSnapshot>> consumers = new ArrayList<>();
    private final Map<String, RollingSeries> buffers = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService sampler;
    private PerformanceFpsHook hook;
    private long lastTickEpochMs = -1L;
    private long lastRenderCalls = -1L;
    private long lastGcCollections = -1L;
    private long lastGcPauseMillis = -1L;
    private final OperatingSystemMXBean osBean;
    private boolean cpuLoadApiInvalid;
    private int invalidCpuLoadCount;
    private long lastCpuTimeNanos = -1L;
    private long lastCpuSampleNanos = -1L;

    public RuntimePerformanceProbeService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final Clock clock
    ) {
        this(
            pluginId,
            permissionChecker,
            clock,
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class)
        );
    }

    RuntimePerformanceProbeService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final Clock clock,
        final OperatingSystemMXBean osBean
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.osBean = Objects.requireNonNull(osBean, "osBean");
        for (String series : List.of(SERIES_CPU, SERIES_FPS, SERIES_HEAP, SERIES_NONHEAP, SERIES_FRAMES, SERIES_GC_PAUSE)) {
            buffers.put(series, new RollingSeries(BUFFER_CAPACITY));
        }
    }

    @Override
    public PerformanceSnapshot snapshot() {
        checkPermission();
        final long now = clock.millis();
        synchronized (lifecycle) {
            return buildSnapshot(now, renderCalls());
        }
    }

    @Override
    public Registration sample(
        final Duration interval,
        final Consumer<PerformanceSnapshot> consumer
    ) {
        checkPermission();
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(consumer, "consumer");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        synchronized (lifecycle) {
            if (consumers.isEmpty()) {
                startSampling(interval);
            }
            consumers.add(consumer);
        }
        return () -> {
            synchronized (lifecycle) {
                consumers.remove(consumer);
                if (consumers.isEmpty()) {
                    stopSampling();
                }
            }
        };
    }

    private void startSampling(final Duration interval) {
        final long now = clock.millis();
        lastTickEpochMs = now;
        lastRenderCalls = renderCalls();
        final long[] gc = gcCounters();
        lastGcCollections = gc[0];
        lastGcPauseMillis = gc[1];
        captureCpuBaseline();
        final PerformanceFpsHook published = PerformanceFpsHookRegistry.installed().orElse(null);
        if (published != null) {
            try {
                published.install();
                hook = published;
            } catch (Throwable failure) {
                System.err.println(
                    "Turboism performance FPS hook disabled safely: " + failure.getClass().getName()
                );
            }
        }
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "turboism-perf-stats-" + pluginId);
                    thread.setDaemon(true);
                    return thread;
                }
            }
        );
        sampler = executor;
        executor.scheduleAtFixedRate(
            this::tick,
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void stopSampling() {
        final ScheduledExecutorService executor = sampler;
        sampler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        final PerformanceFpsHook mounted = hook;
        hook = null;
        lastTickEpochMs = -1L;
        lastRenderCalls = -1L;
        lastGcCollections = -1L;
        lastGcPauseMillis = -1L;
        lastCpuTimeNanos = -1L;
        lastCpuSampleNanos = -1L;
        Throwable failure = null;
        try {
            if (mounted != null) {
                mounted.close();
            }
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        } finally {
            unpublishCharts();
        }
        if (failure != null) {
            throw new IllegalStateException(
                "performance sampling stopped but FPS hook restoration failed",
                failure
            );
        }
    }

    private void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            final long now = clock.millis();
            final PerformanceSnapshot snapshot;
            final double gcPauseWindowMillis;
            synchronized (lifecycle) {
                if (consumers.isEmpty()) return;
                snapshot = buildSnapshot(now, renderCalls());
                lastTickEpochMs = now;
                lastRenderCalls = snapshot.renderedFrames();
                gcPauseWindowMillis = lastGcPauseMillis < 0L
                    ? 0.0
                    : Math.max(0.0, (double) (snapshot.gcPauseMillis() - lastGcPauseMillis));
                lastGcCollections = snapshot.gcCollections();
                lastGcPauseMillis = snapshot.gcPauseMillis();
            }
            appendBuffers(snapshot, gcPauseWindowMillis);
            publishCharts();
            for (Consumer<PerformanceSnapshot> consumer : List.copyOf(consumers)) {
                try {
                    consumer.accept(snapshot);
                } catch (Throwable failure) {
                    System.err.println(
                        "Turboism performance sampling consumer failed safely: "
                            + failure.getClass().getName()
                    );
                }
            }
        } finally {
            running.set(false);
        }
    }

    private long renderCalls() {
        final PerformanceFpsHook active = hook;
        return active == null ? 0L : active.renderSceneCalls();
    }

    private PerformanceSnapshot buildSnapshot(final long now, final long calls) {
        final double fps;
        final long renderedFrames = calls;
        if (lastTickEpochMs >= 0L && lastRenderCalls >= 0L) {
            final double windowSeconds = Math.max(0.001, (now - lastTickEpochMs) / 1000.0);
            fps = Math.max(0.0, (calls - lastRenderCalls) / windowSeconds);
        } else {
            fps = 0.0;
        }
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        final long[] gc = gcCounters();
        return new PerformanceSnapshot(
            now,
            processCpuPercent(),
            heap.getUsed(),
            nonHeap.getUsed(),
            fps,
            renderedFrames,
            0L,
            0L,
            gc[0],
            gc[1]
        );
    }

    /**
     * Sums collection count and collection time across all registered GC
     * MXBeans. Beans without a valid counter (-1) contribute zero so the
     * cumulative totals stay monotonic and fail closed.
     */
    private static long[] gcCounters() {
        long collections = 0L;
        long pauseMillis = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long count = bean.getCollectionCount();
            final long time = bean.getCollectionTime();
            collections += count < 0L ? 0L : count;
            pauseMillis += time < 0L ? 0L : time;
        }
        return new long[] { collections, pauseMillis };
    }

    /**
     * Process CPU percent in [0,100]. The JDK {@link OperatingSystemMXBean}
     * getProcessCpuLoad value is used while valid; once it reports invalid
     * values (NaN/negative — e.g. the PDH query failure under Wine/Proton) on
     * two consecutive samples, the broken query is latched off and samples are
     * derived from getProcessCpuTime() deltas over monotonic elapsed time,
     * normalized by available processors. A sample with no prior baseline is
     * 0; the next sample is non-zero whenever process CPU time advanced.
     */
    private double processCpuPercent() {
        if (!cpuLoadApiInvalid) {
            final double load = osBean.getProcessCpuLoad();
            if (!Double.isNaN(load) && load >= 0.0) {
                return Math.min(100.0, load * 100.0);
            }
            if (++invalidCpuLoadCount < INVALID_CPU_LOAD_LATCH_THRESHOLD) {
                captureCpuBaseline();
                return 0.0;
            }
            cpuLoadApiInvalid = true;
        }
        return cpuPercentByTimeDelta();
    }

    private void captureCpuBaseline() {
        lastCpuTimeNanos = osBean.getProcessCpuTime();
        lastCpuSampleNanos = System.nanoTime();
    }

    private double cpuPercentByTimeDelta() {
        final long cpuTimeNanos = osBean.getProcessCpuTime();
        final long nowNanos = System.nanoTime();
        final long previousCpuTime = lastCpuTimeNanos;
        final long previousSample = lastCpuSampleNanos;
        lastCpuTimeNanos = cpuTimeNanos;
        lastCpuSampleNanos = nowNanos;
        if (cpuTimeNanos < 0L || previousCpuTime < 0L || previousSample < 0L) {
            return 0.0;
        }
        return cpuPercentFromDeltas(
            cpuTimeNanos - previousCpuTime,
            nowNanos - previousSample,
            Runtime.getRuntime().availableProcessors()
        );
    }

    /** Process CPU percent from a CPU-time delta over an elapsed window. Package-private for focused tests. */
    static double cpuPercentFromDeltas(final long cpuTimeDeltaNanos, final long elapsedNanos, final int processors) {
        if (cpuTimeDeltaNanos <= 0L || elapsedNanos <= 0L || processors <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (double) cpuTimeDeltaNanos / (double) elapsedNanos * 100.0 / processors);
    }

    private void appendBuffers(final PerformanceSnapshot snapshot, final double gcPauseWindowMillis) {
        buffers.get(SERIES_CPU).append(snapshot.cpuPercent());
        buffers.get(SERIES_FPS).append(snapshot.fps());
        buffers.get(SERIES_HEAP).append(bytesToMebibytes(snapshot.jvmHeapBytes()));
        buffers.get(SERIES_NONHEAP).append(bytesToMebibytes(snapshot.jvmNonHeapBytes()));
        buffers.get(SERIES_FRAMES).append((double) snapshot.renderedFrames());
        buffers.get(SERIES_GC_PAUSE).append(gcPauseWindowMillis);
    }

    private void publishCharts() {
        ChartDataRegistry.publish(CHART_CPU, chartData(SERIES_CPU));
        ChartDataRegistry.publish(CHART_FPS, chartData(SERIES_FPS));
        ChartDataRegistry.publish(CHART_HEAP, chartData(SERIES_HEAP));
        ChartDataRegistry.publish(CHART_NONHEAP, chartData(SERIES_NONHEAP));
        ChartDataRegistry.publish(CHART_FRAMES, chartData(SERIES_FRAMES));
        ChartDataRegistry.publish(CHART_GC, chartData(SERIES_GC_PAUSE));
    }

    private void unpublishCharts() {
        for (String chartId : List.of(CHART_CPU, CHART_FPS, CHART_HEAP, CHART_NONHEAP, CHART_FRAMES, CHART_GC)) {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    private ChartDataRegistry.ChartData chartData(final String seriesName) {
        return new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData(
                seriesName,
                buffers.get(seriesName).snapshot()
            )
        ));
    }

    private static double bytesToMebibytes(final long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private void checkPermission() {
        permissionChecker.check(PermissionIds.TURBOISM_PERFORMANCE_STATS_READ, "performance.stats.read");
    }

    private static final class RollingSeries {
        private final int capacity;
        private final ArrayDeque<Double> values = new ArrayDeque<>();

        private RollingSeries(final int capacity) {
            this.capacity = capacity;
        }

        private synchronized void append(final double value) {
            if (values.size() == capacity) {
                values.removeFirst();
            }
            values.addLast(value);
        }

        private synchronized List<Double> snapshot() {
            return List.copyOf(values);
        }
    }
}
