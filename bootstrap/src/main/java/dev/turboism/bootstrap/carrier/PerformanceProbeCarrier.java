package dev.turboism.bootstrap.carrier;

/**
 * Static hand-off point between instrumented Cubism bytecode and the runtime's probe.
 *
 * <p>Instrumented call sites are rewritten to call {@link #enter(int)} and
 * {@link #exit(int, long)} directly, so this type lives in the agent's own loader and must stay
 * free of runtime dependencies. Both hot methods short-circuit on a volatile mask read when their
 * metric is disabled, which is the state Cubism runs in unless a probe is explicitly enabled.</p>
 *
 * <p>Cleanup is fail-safe rather than fail-closed: the hot path snapshots the callback before
 * using it, so a concurrent {@link #clear(PerformanceProbeCallback)} can never surface a race
 * inside host code. A disabled or cleared carrier silently measures nothing.</p>
 */
public final class PerformanceProbeCarrier {

    private static volatile PerformanceProbeCallback callback;
    private static volatile long enabledMask;

    private PerformanceProbeCarrier() { }

    /**
     * Installs the single process-wide callback.
     *
     * @param installed the callback instrumented call sites will reach
     * @throws IllegalStateException when a callback is already installed; the carrier is
     *     deliberately single-owner so two probes cannot silently replace each other
     */
    public static void install(final PerformanceProbeCallback installed) {
        if (callback != null) throw new IllegalStateException("performance probe callback already installed");
        callback = installed;
    }

    /**
     * Enables measurement for the metrics selected by a bit mask.
     *
     * @param metricMask bit {@code n} enables metric id {@code n}
     */
    public static void enable(final long metricMask) {
        enabledMask = metricMask;
    }

    /** Disables measurement for every metric, leaving the callback installed. */
    public static void disable() {
        enabledMask = 0L;
    }

    /**
     * Disables measurement and removes the callback if it is still the installed one.
     *
     * @param installed the callback to remove; a stale value is ignored so a late cleanup from a
     *     replaced owner cannot detach a newer probe
     */
    public static void clear(final PerformanceProbeCallback installed) {
        disable();
        if (callback == installed) callback = null;
    }

    /**
     * Entry hook invoked by instrumented Cubism bytecode.
     *
     * @param metricId the metric this call site belongs to
     * @return an opaque start stamp to pass to {@link #exit(int, long)}, or {@code 0} when the
     *     metric is disabled or no callback is installed
     */
    public static long enter(final int metricId) {
        if ((enabledMask & (1L << metricId)) == 0L) return 0L;
        // Snapshot: a concurrent clear() must never let cleanup races escape into Cubism.
        final PerformanceProbeCallback active = callback;
        return active == null ? 0L : active.enter(metricId);
    }

    /**
     * Exit hook invoked by instrumented Cubism bytecode.
     *
     * @param metricId the metric this call site belongs to
     * @param startedNanos the stamp returned by the matching {@link #enter(int)}; {@code 0} means
     *     the sample was never started and is ignored
     */
    public static void exit(final int metricId, final long startedNanos) {
        if (startedNanos == 0L) return;
        final PerformanceProbeCallback active = callback;
        if (active != null) active.exit(metricId, startedNanos);
    }
}
