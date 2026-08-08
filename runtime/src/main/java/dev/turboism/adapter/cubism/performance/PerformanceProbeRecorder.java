package dev.turboism.adapter.cubism.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

public final class PerformanceProbeRecorder {

    private final AtomicBoolean capturing = new AtomicBoolean();
    private final AtomicLong inFlight = new AtomicLong();
    private final EnumMap<PerformanceProbeMetric, Metric> metrics = new EnumMap<>(PerformanceProbeMetric.class);
    private final LongAdder failures = new LongAdder();
    private final LongAdder renderSceneCalls = new LongAdder();

    public PerformanceProbeRecorder() {
        for (PerformanceProbeMetric metric : PerformanceProbeMetric.values()) metrics.put(metric, new Metric());
    }

    public boolean startCapture() {
        if (!capturing.compareAndSet(false, true)) return false;
        metrics.values().forEach(Metric::reset);
        failures.reset();
        inFlight.set(0L);
        return true;
    }

    public void stopCapture() {
        capturing.set(false);
    }

    public boolean awaitQuiescence(final long timeoutMillis) {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (inFlight.get() != 0L && System.nanoTime() < deadline) {
            // Bounded park instead of a tight spin: the reporter thread must not
            // burn a CPU core while waiting for late exits.
            LockSupport.parkNanos(1_000_000L);
        }
        return inFlight.get() == 0L;
    }

    public void fail() {
        failures.increment();
        stopCapture();
    }

    /**
     * Cumulative real-time renderScene call counter. Incremented on every
     * RENDER_SCENE entry regardless of {@link #startCapture() capture state},
     * so FPS can be derived as calls per wall-clock window while the probe is
     * installed but not capturing. Never reset by the capture lifecycle.
     */
    public long renderSceneCalls() {
        return renderSceneCalls.sum();
    }

    long enter(final PerformanceProbeMetric metric) {
        if (metric == PerformanceProbeMetric.RENDER_SCENE) {
            renderSceneCalls.increment();
        }
        if (!capturing.get()) return 0L;
        inFlight.incrementAndGet();
        if (!capturing.get()) {
            inFlight.decrementAndGet();
            return 0L;
        }
        final Metric state = metrics.get(metric);
        final long call = state.calls.incrementAndGet();
        if ((call - 1L) % metric.sampleEvery() != 0L) return Long.MIN_VALUE;
        state.sampled.increment();
        return System.nanoTime();
    }

    void exit(final PerformanceProbeMetric metric, final long started) {
        try {
            if (started > 0L) {
                final long elapsed = Math.max(0L, System.nanoTime() - started);
                final Metric state = metrics.get(metric);
                state.totalNanos.add(elapsed);
                state.maxNanos.accumulateAndGet(elapsed, Math::max);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public Snapshot snapshot() {
        final EnumMap<PerformanceProbeMetric, MetricSnapshot> copy = new EnumMap<>(PerformanceProbeMetric.class);
        metrics.forEach((metric, value) -> copy.put(metric, value.snapshot()));
        return new Snapshot(Map.copyOf(copy), failures.sum());
    }

    public record Snapshot(Map<PerformanceProbeMetric, MetricSnapshot> metrics, long failures) { }

    public record MetricSnapshot(long calls, long sampled, long totalNanos, long maxNanos) { }

    private static final class Metric {
        private final AtomicLong calls = new AtomicLong();
        private final LongAdder sampled = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        private void reset() {
            calls.set(0L);
            sampled.reset();
            totalNanos.reset();
            maxNanos.set(0L);
        }

        private MetricSnapshot snapshot() {
            return new MetricSnapshot(calls.get(), sampled.sum(), totalNanos.sum(), maxNanos.get());
        }
    }
}
