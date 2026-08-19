package dev.turboism.bootstrap.carrier;

/**
 * Sink for instrumented Cubism method timings.
 *
 * <p>Implementations run inside instrumented host methods on the caller's thread, so both methods
 * must stay allocation-light and must not block, take locks held by host code, or throw. The
 * carrier only forwards to a callback while a metric is enabled.</p>
 */
public interface PerformanceProbeCallback {

    /**
     * Records entry into an instrumented method.
     *
     * @param metricId the metric this call site belongs to
     * @return an opaque start stamp to hand back to {@link #exit(int, long)}, or {@code 0} when
     *     this sample is not being measured
     */
    long enter(int metricId);

    /**
     * Records exit from an instrumented method.
     *
     * @param metricId the metric this call site belongs to
     * @param startedNanos the non-zero stamp returned by the matching {@link #enter(int)}
     */
    void exit(int metricId, long startedNanos);
}
