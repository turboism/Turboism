package dev.turboism.adapter.cubism.performance;

import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-size base-2 histogram; recording has no allocations and stores no host references. */
final class PerformanceLatencyHistogram {
    private final AtomicLongArray buckets = new AtomicLongArray(64);

    void record(final long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("duration must not be negative");
        final int bucket = nanos == 0 ? 0 : Long.SIZE - Long.numberOfLeadingZeros(nanos);
        buckets.incrementAndGet(bucket);
    }

    void reset() {
        for (int index = 0; index < buckets.length(); index++) buckets.set(index, 0);
    }

    PerformanceProbeRecorder.LatencySnapshot snapshot() {
        final long[] counts = new long[buckets.length()];
        long samples = 0;
        for (int index = 0; index < counts.length; index++) {
            counts[index] = buckets.get(index);
            samples += counts[index];
        }
        return new PerformanceProbeRecorder.LatencySnapshot(samples,
            bound(counts, samples, 50), bound(counts, samples, 95), bound(counts, samples, 99));
    }

    private static long bound(final long[] counts, final long samples, final int percentile) {
        if (samples == 0) return 0;
        final long rank = samples / 100 * percentile + (samples % 100 * percentile + 99) / 100;
        long cumulative = 0;
        for (int index = 0; index < counts.length; index++) {
            cumulative += counts[index];
            if (cumulative >= rank) return index == 63 ? Long.MAX_VALUE : (1L << index) - 1;
        }
        throw new IllegalStateException("histogram sample count mismatch");
    }
}
