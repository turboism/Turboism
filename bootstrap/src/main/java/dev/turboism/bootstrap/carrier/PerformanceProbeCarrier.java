package dev.turboism.bootstrap.carrier;

public final class PerformanceProbeCarrier {

    private static volatile PerformanceProbeCallback callback;
    private static volatile long enabledMask;

    private PerformanceProbeCarrier() { }

    public static void install(final PerformanceProbeCallback installed) {
        if (callback != null) throw new IllegalStateException("performance probe callback already installed");
        callback = installed;
    }

    public static void enable(final long metricMask) {
        enabledMask = metricMask;
    }

    public static void disable() {
        enabledMask = 0L;
    }

    public static void clear(final PerformanceProbeCallback installed) {
        disable();
        if (callback == installed) callback = null;
    }

    public static long enter(final int metricId) {
        if ((enabledMask & (1L << metricId)) == 0L) return 0L;
        // Snapshot: a concurrent clear() must never let cleanup races escape into Cubism.
        final PerformanceProbeCallback active = callback;
        return active == null ? 0L : active.enter(metricId);
    }

    public static void exit(final int metricId, final long startedNanos) {
        if (startedNanos == 0L) return;
        final PerformanceProbeCallback active = callback;
        if (active != null) active.exit(metricId, startedNanos);
    }
}
