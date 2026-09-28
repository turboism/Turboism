package dev.turboism.validation.externalpsd;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * Validation-only observer of EDT queue delay and sampled JVM memory. It neither accesses nor
 * mutates model state. At most one heartbeat is outstanding, including when the EDT is blocked.
 * Memory values are sampled peaks, not RSS; zero samples and interrupted coverage are explicit.
 */
final class ExternalPsdPerformanceSampler implements AutoCloseable {
    static final long PERIOD_MILLIS = 10L;
    private final LongSupplier clock;
    private final LongSupplier heap;
    private final LongSupplier nonHeap;
    private final Consumer<Runnable> edt;
    private final long started;
    private ScheduledExecutorService executor;
    private boolean active = true;
    private boolean accepting = true;
    private boolean pending;
    private long pendingSince;
    private long closedAt;
    private long lastTick;
    private boolean hasTick;
    private long samples;
    private long memorySamples;
    private long maxQueueNanos;
    private long maxTickGapNanos;
    private long peakHeap;
    private long peakNonHeap;
    private long failures;
    private String diagnostic = "";

    static ExternalPsdPerformanceSampler start() {
        final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        final ExternalPsdPerformanceSampler sampler = new ExternalPsdPerformanceSampler(
            System::nanoTime, () -> memory.getHeapMemoryUsage().getUsed(),
            () -> memory.getNonHeapMemoryUsage().getUsed(), SwingUtilities::invokeLater);
        sampler.executor = Executors.newSingleThreadScheduledExecutor(operation -> {
            final Thread thread = new Thread(operation, "external-psd-validation-performance");
            thread.setDaemon(true);
            return thread;
        });
        sampler.executor.scheduleWithFixedDelay(sampler::tick, 0L, PERIOD_MILLIS,
            TimeUnit.MILLISECONDS);
        return sampler;
    }

    /** Deterministic seam; production uses the same tick and completion code. */
    ExternalPsdPerformanceSampler(final LongSupplier clock, final LongSupplier heap,
        final LongSupplier nonHeap, final Consumer<Runnable> edt) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heap = Objects.requireNonNull(heap, "heap");
        this.nonHeap = Objects.requireNonNull(nonHeap, "nonHeap");
        this.edt = Objects.requireNonNull(edt, "edt");
        started = clock.getAsLong();
    }

    void tick() {
        final long queuedAt;
        synchronized (this) {
            if (!active || !accepting) return;
            try {
                final long now = clock.getAsLong();
                if (hasTick) maxTickGapNanos = Math.max(maxTickGapNanos, elapsed(now, lastTick));
                lastTick = now;
                hasTick = true;
                final long heapBytes = heap.getAsLong();
                final long nonHeapBytes = nonHeap.getAsLong();
                if (heapBytes < 0L || nonHeapBytes < 0L) {
                    throw new IllegalStateException("memory sample is unavailable");
                }
                peakHeap = Math.max(peakHeap, heapBytes);
                peakNonHeap = Math.max(peakNonHeap, nonHeapBytes);
                memorySamples++;
                if (pending) return;
                queuedAt = now;
                pendingSince = queuedAt;
                pending = true;
            } catch (RuntimeException failure) {
                recordFailure(failure);
                return;
            }
        }
        try {
            edt.accept(() -> completeHeartbeat(queuedAt));
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (!active) return;
                pending = false;
                recordFailure(failure);
                notifyAll();
            }
        }
    }

    private synchronized void completeHeartbeat(final long queuedAt) {
        if (!active || !pending || queuedAt != pendingSince) return;
        try {
            maxQueueNanos = Math.max(maxQueueNanos, elapsed(clock.getAsLong(), queuedAt));
            samples++;
        } catch (RuntimeException failure) {
            recordFailure(failure);
        } finally {
            pending = false;
            notifyAll();
        }
    }

    /** Stop admission, drain the one existing heartbeat within a bound, then freeze evidence. */
    Snapshot finish(final long timeoutMillis) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("performance observer must finish off EDT");
        }
        if (timeoutMillis <= 0L || timeoutMillis > 10_000L) {
            throw new IllegalArgumentException("finish timeout must be within 1..10000 ms");
        }
        final ScheduledExecutorService stoppedExecutor;
        synchronized (this) {
            accepting = false;
            stoppedExecutor = executor;
        }
        if (stoppedExecutor != null) stoppedExecutor.shutdownNow();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            synchronized (this) {
                while (active && pending) {
                    final long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) break;
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            }
        } finally {
            close();
        }
        return snapshot();
    }

    synchronized Snapshot snapshot() {
        final long end = active ? clock.getAsLong() : closedAt;
        return new Snapshot(samples, memorySamples, pending, maxQueueNanos,
            pending ? elapsed(end, pendingSince) : 0L, maxTickGapNanos,
            peakHeap, peakNonHeap, elapsed(end, started), failures, diagnostic);
    }

    @Override public void close() {
        final ScheduledExecutorService stoppedExecutor;
        synchronized (this) {
            if (!active) return;
            closedAt = clock.getAsLong();
            active = false;
            accepting = false;
            notifyAll();
            // A heartbeat still pending at stop remains an explicit coverage gap. A later EDT
            // callback cannot turn that gap into a successful measurement after the fact.
            stoppedExecutor = executor;
        }
        if (stoppedExecutor != null) stoppedExecutor.shutdownNow();
    }

    private void recordFailure(final RuntimeException failure) {
        failures++;
        diagnostic = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static long elapsed(final long end, final long start) {
        final long result = end - start;
        if (result < 0L) throw new IllegalStateException("monotonic clock moved backwards");
        return result;
    }

    record Snapshot(long heartbeatSamples, long memorySamples, boolean heartbeatPending,
        long maximumQueueDelayNanos, long pendingHeartbeatAgeNanos, long maximumTickGapNanos,
        long peakHeapBytes, long peakNonHeapBytes, long elapsedNanos, long failures,
        String diagnostic) {
        boolean complete() {
            return heartbeatSamples > 0L && memorySamples > 0L && !heartbeatPending
                && failures == 0L;
        }
    }
}
