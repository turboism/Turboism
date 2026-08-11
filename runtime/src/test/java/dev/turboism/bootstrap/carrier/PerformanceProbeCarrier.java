package dev.turboism.bootstrap.carrier;

public final class PerformanceProbeCarrier {
    private static volatile PerformanceProbeCallback callback;
    private static volatile long enabledMask;
    private PerformanceProbeCarrier() { }
    public static void install(final PerformanceProbeCallback value) { callback = value; }
    public static void enable(final long mask) { enabledMask = mask; }
    public static void disable() { enabledMask = 0L; }
    public static void clear(final PerformanceProbeCallback value) { disable(); if (callback == value) callback = null; }
    public static long enter(final int metricId) {
        if ((enabledMask & (1L << metricId)) == 0L) return 0L;
        final PerformanceProbeCallback value = callback;
        return value == null ? 0L : value.enter(metricId);
    }
    public static void exit(final int metricId, final long startedNanos) {
        if (startedNanos == 0L) return;
        final PerformanceProbeCallback value = callback;
        if (value != null) value.exit(metricId, startedNanos);
    }
}
