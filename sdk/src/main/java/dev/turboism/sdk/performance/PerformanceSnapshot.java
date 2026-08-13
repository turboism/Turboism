package dev.turboism.sdk.performance;

/**
 * Immutable point-in-time measurement of the Cubism editor process.
 *
 * <p>{@code cpuPercent} and {@code fps} come from real measurement of the
 * Cubism process; {@code jvmHeapBytes}/{@code jvmNonHeapBytes} are read from
 * the JVM memory MXBean. {@code gcCollections} and {@code gcPauseMillis} are
 * cumulative counters summed across all GC MXBeans, so they only ever
 * increase (the sampling service diffs consecutive snapshots to derive
 * per-window values). {@code diskReadBytes} and {@code diskWriteBytes} are
 * reserved placeholders: this slice does not collect disk I/O, so callers must
 * treat them as unbound/no-data and not draw conclusions from them (planned
 * for a later phase).
 */
public record PerformanceSnapshot(
    long timestampEpochMs,
    double cpuPercent,
    long jvmHeapBytes,
    long jvmNonHeapBytes,
    double fps,
    long renderedFrames,
    long diskReadBytes,
    long diskWriteBytes,
    long gcCollections,
    long gcPauseMillis
) {

    public PerformanceSnapshot {
        if (timestampEpochMs < 0L) {
            throw new IllegalArgumentException("timestampEpochMs must not be negative");
        }
        if (Double.isNaN(cpuPercent) || cpuPercent < 0.0 || cpuPercent > 100.0) {
            throw new IllegalArgumentException("cpuPercent must be within [0, 100]");
        }
        if (jvmHeapBytes < 0L || jvmNonHeapBytes < 0L) {
            throw new IllegalArgumentException("JVM memory bytes must not be negative");
        }
        if (Double.isNaN(fps) || fps < 0.0) {
            throw new IllegalArgumentException("fps must not be negative");
        }
        if (renderedFrames < 0L || diskReadBytes < 0L || diskWriteBytes < 0L
            || gcCollections < 0L || gcPauseMillis < 0L) {
            throw new IllegalArgumentException("frame, disk, and GC counters must not be negative");
        }
    }

    @Override
    public String toString() {
        return "PerformanceSnapshot[timestampEpochMs=" + timestampEpochMs
            + ", cpuPercent=" + cpuPercent
            + ", jvmHeapBytes=" + jvmHeapBytes
            + ", jvmNonHeapBytes=" + jvmNonHeapBytes
            + ", fps=" + fps
            + ", renderedFrames=" + renderedFrames
            + ", diskReadBytes=" + diskReadBytes
            + ", diskWriteBytes=" + diskWriteBytes
            + ", gcCollections=" + gcCollections
            + ", gcPauseMillis=" + gcPauseMillis
            + "]";
    }

    /** Convenience factory for tests and diagnostics; GC counters default to zero. */
    public static PerformanceSnapshot of(
        final long timestampEpochMs,
        final double cpuPercent,
        final long jvmHeapBytes,
        final long jvmNonHeapBytes,
        final double fps,
        final long renderedFrames
    ) {
        return new PerformanceSnapshot(
            timestampEpochMs, cpuPercent, jvmHeapBytes, jvmNonHeapBytes,
            fps, renderedFrames, 0L, 0L, 0L, 0L
        );
    }

    /**
     * The fail-closed empty snapshot: all metrics are zero and the disk
     * placeholders are unbound. Used by {@link PerformanceProbeService#unavailable()}.
     */
    public static PerformanceSnapshot unavailable(final long timestampEpochMs) {
        return new PerformanceSnapshot(timestampEpochMs, 0.0, 0L, 0L, 0.0, 0L, 0L, 0L, 0L, 0L);
    }
}
