package dev.turboism.performance;

import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHookRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.panel.ChartDataRegistry;

import com.sun.management.OperatingSystemMXBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePerformanceProbeServiceTest {

    private final AtomicLong calls = new AtomicLong();
    private final AtomicBoolean hookInstalled = new AtomicBoolean();
    private final AtomicBoolean hookClosed = new AtomicBoolean();
    private final PerformanceFpsHook fakeHook = new PerformanceFpsHook() {
        @Override
        public void install() {
            hookInstalled.set(true);
        }

        @Override
        public boolean isInstalled() {
            return hookInstalled.get();
        }

        @Override
        public long renderSceneCalls() {
            return calls.get();
        }

        @Override
        public void close() {
            hookInstalled.set(false);
            hookClosed.set(true);
        }
    };

    /** Fake OS bean: configurable load; process CPU time advances 1_000 ns per query. */
    private static final class FakeOsBean implements OperatingSystemMXBean {
        private final double load;
        private long cpuTimeNanos;
        private int loadQueries;
        private int cpuTimeQueries;

        private FakeOsBean(final double load) {
            this.load = load;
        }

        @Override public double getProcessCpuLoad() { loadQueries++; return load; }
        @Override public long getProcessCpuTime() { cpuTimeQueries++; cpuTimeNanos += 1_000L; return cpuTimeNanos; }
        @Override public long getCommittedVirtualMemorySize() { return 0L; }
        @Override public double getCpuLoad() { return Double.NaN; }
        @Override public long getFreeMemorySize() { return 0L; }

        @Override public long getFreeSwapSpaceSize() { return 0L; }
        @Override public long getTotalMemorySize() { return 0L; }
        @Override public long getTotalSwapSpaceSize() { return 0L; }
        @Override public String getArch() { return ""; }
        @Override public int getAvailableProcessors() { return 1; }
        @Override public String getName() { return ""; }
        @Override public double getSystemLoadAverage() { return 0.0; }

        @Override public String getVersion() { return ""; }
        @Override public ObjectName getObjectName() { return null; }
    }

    @BeforeEach
    void publishHook() {
        PerformanceFpsHookRegistry.publish(fakeHook);
    }

    @AfterEach
    void clearHook() {
        PerformanceFpsHookRegistry.clear(fakeHook);
        hookInstalled.set(false);
        hookClosed.set(false);
        calls.set(0L);
        for (String id : List.of(
            RuntimePerformanceProbeService.CHART_CPU,
            RuntimePerformanceProbeService.CHART_FPS,
            RuntimePerformanceProbeService.CHART_HEAP,
            RuntimePerformanceProbeService.CHART_NONHEAP,
            RuntimePerformanceProbeService.CHART_FRAMES
        )) {
            ChartDataRegistry.unpublish(id);
        }
    }

    private static RuntimePerformanceProbeService service(final PermissionChecker checker) {
        return new RuntimePerformanceProbeService("perf-stats", checker, Clock.systemUTC());
    }

    private static PermissionChecker granted() {
        return PermissionChecker.from(List.of(permission()));
    }

    private static RuntimePerformanceProbeService service(
        final PermissionChecker checker,
        final OperatingSystemMXBean osBean
    ) {
        return new RuntimePerformanceProbeService("perf-stats", checker, Clock.systemUTC(), osBean);
    }

    private static PluginPermission permission() {
        return new PluginPermission() {
            @Override public String id() {
                return "turboism.performance.stats.read";
            }
            @Override public String scope() {
                return "application";
            }
            @Override public String reason() {
                return "test";
            }
        };
    }

    @Test
    void snapshotReturnsRealMeasurements() {
        final PerformanceSnapshot snapshot = service(granted()).snapshot();
        assertTrue(snapshot.cpuPercent() >= 0.0 && snapshot.cpuPercent() <= 100.0);
        assertTrue(snapshot.jvmHeapBytes() > 0L);
        assertTrue(snapshot.jvmNonHeapBytes() > 0L);
        assertEquals(0L, snapshot.diskReadBytes());
        assertEquals(0L, snapshot.diskWriteBytes());
    }

    @Test
    void cpuLoadFallsBackToProcessCpuTimeDeltasAfterInvalidLoad() {
        final FakeOsBean os = new FakeOsBean(Double.NaN);
        final RuntimePerformanceProbeService service = service(granted(), os);
        // First sample seeds the delta baseline and is allowed to be 0.
        assertEquals(0.0, service.snapshot().cpuPercent());
        assertEquals(1, os.loadQueries);
        // Second sample latches the fallback and must be non-zero as CPU time advanced.
        final double second = service.snapshot().cpuPercent();
        assertTrue(second > 0.0 && second <= 100.0, "fallback must report real CPU %, was " + second);
        assertEquals(2, os.loadQueries, "invalid load API must not be queried again after latching");
        // Later samples keep using the fallback without re-querying the broken API.
        final double third = service.snapshot().cpuPercent();
        assertTrue(third > 0.0 && third <= 100.0);
        assertEquals(2, os.loadQueries);
    }

    @Test
    void validCpuLoadApiIsPreferredAndFallbackIsNotQueried() {
        final FakeOsBean os = new FakeOsBean(0.5);
        final RuntimePerformanceProbeService service = service(granted(), os);
        assertEquals(50.0, service.snapshot().cpuPercent(), 0.0);
        assertEquals(0, os.cpuTimeQueries, "time-delta fallback must not run while the load API is valid");
    }

    @Test
    void cpuFallbackBaselineResetsBetweenSamplingSessions() throws Exception {
        final FakeOsBean os = new FakeOsBean(Double.NaN);
        final RuntimePerformanceProbeService service = service(granted(), os);
        final CopyOnWriteArrayList<PerformanceSnapshot> first = new CopyOnWriteArrayList<>();
        final Registration firstRegistration = service.sample(Duration.ofMillis(30), first::add);
        await(() -> first.size() >= 2);
        assertEquals(0.0, first.get(0).cpuPercent(), "first tick seeds the fallback baseline");
        assertTrue(first.get(1).cpuPercent() > 0.0, "second tick must report real CPU % from the delta fallback");
        firstRegistration.close();

        final CopyOnWriteArrayList<PerformanceSnapshot> second = new CopyOnWriteArrayList<>();
        final Registration secondRegistration = service.sample(Duration.ofMillis(30), second::add);
        try {
            await(() -> second.size() >= 2);
            // The new session re-baselines: its first sample must not include
            // CPU time consumed before the session restart.
            assertTrue(
                second.get(0).cpuPercent() < 1.0,
                "stale CPU baseline leaked into the restarted session, was " + second.get(0).cpuPercent()
            );
        } finally {
            secondRegistration.close();
        }
    }

    @Test
    void cpuPercentFromDeltasComputesProcessCpuPercent() {
        assertEquals(0.05, RuntimePerformanceProbeService.cpuPercentFromDeltas(2_000_000L, 1_000_000_000L, 4), 0.0);
        assertEquals(100.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(1_000_000_000L, 1_000_000_000L, 1), 0.0);
        assertEquals(100.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(2_000_000_000L, 1_000_000_000L, 1), 0.0);
        assertEquals(0.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(0L, 1_000_000_000L, 1), 0.0);
        assertEquals(0.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(1_000L, 0L, 1), 0.0);
        assertEquals(0.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(-1L, 1_000L, 1), 0.0);
        assertEquals(0.0, RuntimePerformanceProbeService.cpuPercentFromDeltas(1_000L, 1_000L, 0), 0.0);
    }

    @Test
    void snapshotFailsClosedWithoutPermission() {
        final RuntimePerformanceProbeService service = service(PermissionChecker.from(List.of()));
        assertThrows(CubismPermissionException.class, service::snapshot);
    }

    @Test
    void sampleDeliversSnapshotsAndCloseStopsAndUnmounts() throws Exception {
        final RuntimePerformanceProbeService service = service(granted());
        final CopyOnWriteArrayList<PerformanceSnapshot> received = new CopyOnWriteArrayList<>();
        final Registration registration = service.sample(
            Duration.ofMillis(50),
            received::add
        );
        try {
            await(() -> received.size() >= 2);
            assertTrue(hookInstalled.get(), "FPS hook must be mounted while sampling");
            assertTrue(ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_CPU).isPresent());
            assertTrue(ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_FPS).isPresent());
            assertTrue(ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_HEAP).isPresent());
        } finally {
            registration.close();
        }
        final int settled = received.size();
        Thread.sleep(150L);
        assertEquals(settled, received.size(), "callbacks must stop after close");
        assertTrue(hookClosed.get(), "FPS hook must be unmounted when sampling stops");
        assertFalse(ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_CPU).isPresent());
        assertFalse(ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_GC).isPresent());
    }

    @Test
    void fpsIsDerivedFromRenderSceneCallDeltas() throws Exception {
        final RuntimePerformanceProbeService service = service(granted());
        final CopyOnWriteArrayList<PerformanceSnapshot> received = new CopyOnWriteArrayList<>();
        final Registration registration = service.sample(Duration.ofMillis(50), received::add);
        try {
            await(() -> received.size() >= 1);
            calls.set(calls.get() + 100L);
            await(() -> received.size() >= 2);
            final PerformanceSnapshot latest = received.get(received.size() - 1);
            // 100 calls over the ~50ms window => ~2000 fps; must be positive and real.
            assertTrue(latest.fps() > 0.0, "fps must derive from real counter deltas, was " + latest.fps());
            assertTrue(latest.renderedFrames() >= 100L);
        } finally {
            registration.close();
        }
    }

    @Test
    void fpsIsZeroWithoutMountedHook() {
        PerformanceFpsHookRegistry.clear(fakeHook);
        final PerformanceSnapshot snapshot = service(granted()).snapshot();
        assertEquals(0.0, snapshot.fps());
        assertEquals(0L, snapshot.renderedFrames());
    }


    @Test
    void gcCountersAreCumulativeAndMonotonic() throws Exception {
        final RuntimePerformanceProbeService service = service(granted());
        final CopyOnWriteArrayList<PerformanceSnapshot> received = new CopyOnWriteArrayList<>();
        final Registration registration = service.sample(Duration.ofMillis(30), received::add);
        try {
            await(() -> received.size() >= 3);
            for (PerformanceSnapshot snapshot : received) {
                assertTrue(snapshot.gcCollections() >= 0L);
                assertTrue(snapshot.gcPauseMillis() >= 0L);
            }
            for (int i = 1; i < received.size(); i++) {
                assertTrue(received.get(i).gcCollections() >= received.get(i - 1).gcCollections(),
                    "gcCollections must never decrease");
                assertTrue(received.get(i).gcPauseMillis() >= received.get(i - 1).gcPauseMillis(),
                    "gcPauseMillis must never decrease");
            }
        } finally {
            registration.close();
        }
    }

    @Test
    void gcPauseSeriesTracksCumulativeDeltas() throws Exception {
        final RuntimePerformanceProbeService service = service(granted());
        final CopyOnWriteArrayList<PerformanceSnapshot> received = new CopyOnWriteArrayList<>();
        final Registration registration = service.sample(Duration.ofMillis(30), received::add);
        try {
            await(() -> received.size() >= 3);
            final List<Double> values = ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_GC)
                .orElseThrow(() -> new AssertionError("GC Pause chart must be published while sampling"))
                .series().get(0).values();
            assertFalse(values.isEmpty());
            final int comparable = Math.min(values.size(), received.size());
            for (int i = 1; i < comparable; i++) {
                assertEquals(
                    (double) (received.get(i).gcPauseMillis() - received.get(i - 1).gcPauseMillis()),
                    values.get(i),
                    0.0,
                    "window pause must equal the cumulative delta between consecutive ticks");
            }
            for (double value : values) {
                assertTrue(value >= 0.0, "window pause must never be negative");
            }
        } finally {
            registration.close();
        }
    }

    @Test
    void gcWindowBaselineResetsBetweenSamplingSessions() throws Exception {
        final RuntimePerformanceProbeService service = service(granted());
        final CopyOnWriteArrayList<PerformanceSnapshot> first = new CopyOnWriteArrayList<>();
        final Registration firstRegistration = service.sample(Duration.ofMillis(30), first::add);
        await(() -> first.size() >= 3);
        firstRegistration.close();
        final long sessionEndPauseMillis = first.get(first.size() - 1).gcPauseMillis();

        // Force a real GC so the cumulative pause counter strictly advances.
        forceGc();
        final long advancedPauseMillis = service.snapshot().gcPauseMillis();
        if (advancedPauseMillis <= sessionEndPauseMillis) {
            return; // JVM did not honor explicit GC; nothing observable to verify.
        }

        // The first window of the new session is measured from the fresh
        // start-of-session baseline, so it must exclude the forced-GC pause
        // that happened before sampling restarted.
        final CopyOnWriteArrayList<PerformanceSnapshot> second = new CopyOnWriteArrayList<>();
        final Registration secondRegistration = service.sample(Duration.ofMillis(30), second::add);
        try {
            await(() -> second.size() >= 2);
            final double firstWindow = ChartDataRegistry.find(RuntimePerformanceProbeService.CHART_GC)
                .orElseThrow(() -> new AssertionError("GC Pause chart must be published while sampling"))
                .series().get(0).values().get(0);
            final double withoutReset = second.get(0).gcPauseMillis() - sessionEndPauseMillis;
            assertTrue(firstWindow < withoutReset,
                "first window pause must exclude pauses before the new session baseline, was " + firstWindow);
        } finally {
            secondRegistration.close();
        }
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        System.runFinalization();
        System.gc();
        Thread.sleep(100L);
    }

    private static void await(final java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(20L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not reached within 5s");
        }
    }
}
