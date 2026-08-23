package dev.turboism.adapter.cubism.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Accumulates probe timings written from the Cubism render thread.
 *
 * <p>Counters are lock-free atomics, so the enter/exit path adds no blocking to host
 * code. Capture is a toggle: only one {@link #startCapture()} can win, and starting
 * resets all metrics. Because a call may be in flight when capture stops,
 * {@link #awaitQuiescence(long)} lets a reporter wait for the outstanding exits before
 * taking a snapshot.</p>
 *
 * <p>Any failure inside the probe calls {@link #fail()}, which stops capture rather
 * than risk skewed or unsafe measurement.</p>
 */
public final class PerformanceProbeRecorder {

    private final AtomicBoolean capturing = new AtomicBoolean();
    private final AtomicLong inFlight = new AtomicLong();
    private final EnumMap<PerformanceProbeMetric, Metric> metrics = new EnumMap<>(PerformanceProbeMetric.class);
    private final LongAdder failures = new LongAdder();
    private final LongAdder renderSceneCalls = new LongAdder();

    public PerformanceProbeRecorder() {
        for (PerformanceProbeMetric metric : PerformanceProbeMetric.values()) metrics.put(metric, new Metric());
    }

    /**
     * Begins a capture window, clearing all metric counters, the failure count, and the
     * in-flight count. The cumulative renderScene call counter is deliberately not reset.
     *
     * @return {@code true} when this call started the capture; {@code false} when a
     *     capture was already running, in which case nothing was reset
     */
    public boolean startCapture() {
        if (!capturing.compareAndSet(false, true)) return false;
        metrics.values().forEach(Metric::reset);
        failures.reset();
        inFlight.set(0L);
        return true;
    }

    /**
     * Ends the capture window. Calls already inside an instrumented method may still
     * record their exit, so accumulated values keep changing briefly; use
     * {@link #awaitQuiescence(long)} before snapshotting. Idempotent.
     */
    public void stopCapture() {
        capturing.set(false);
    }

    /**
     * Waits for every instrumented call that already entered to finish, parking in 1 ms
     * steps rather than spinning so the render thread keeps its core.
     *
     * @param timeoutMillis how long to wait at most
     * @return {@code true} when no calls are in flight; {@code false} when the deadline
     *     passed first, meaning a snapshot taken now may still change
     */
    public boolean awaitQuiescence(final long timeoutMillis) {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (inFlight.get() != 0L && System.nanoTime() < deadline) {
            // Bounded park instead of a tight spin: the reporter thread must not
            // burn a CPU core while waiting for late exits.
            LockSupport.parkNanos(1_000_000L);
        }
        return inFlight.get() == 0L;
    }

    /**
     * Records that the probe itself misbehaved and stops the capture, so a damaged
     * measurement is never reported as a good one. The failure count survives into the
     * snapshot.
     */
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

    /**
     * @return an immutable copy of every metric counters plus the failure count, taken
     *     without stopping the capture; values are only mutually consistent once
     *     {@link #awaitQuiescence(long)} has confirmed quiescence
     */
    public Snapshot snapshot() {
        final EnumMap<PerformanceProbeMetric, MetricSnapshot> copy = new EnumMap<>(PerformanceProbeMetric.class);
        metrics.forEach((metric, value) -> copy.put(metric, value.snapshot()));
        return new Snapshot(Map.copyOf(copy), failures.sum());
    }

    /**
     * One immutable reading of the whole recorder.
     *
     * @param metrics  per-metric counters, one entry for every
     *                 {@link PerformanceProbeMetric}
     * @param failures how many probe failures occurred during the capture; any non-zero
     *                 value means the capture was cut short
     */
    public record Snapshot(Map<PerformanceProbeMetric, MetricSnapshot> metrics, long failures) { }

    /**
     * Counters for one metric within a capture window.
     *
     * @param calls      how many times the instrumented method was entered while capturing
     * @param sampled    how many of those calls were actually timed; for sampled metrics
     *                   this is roughly {@code calls / sampleEvery}
     * @param totalNanos summed elapsed time over the sampled calls only - divide by
     *                   {@code sampled}, not {@code calls}, for a mean
     * @param maxNanos   the longest single sampled call, or {@code 0} when nothing was sampled
     */
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
