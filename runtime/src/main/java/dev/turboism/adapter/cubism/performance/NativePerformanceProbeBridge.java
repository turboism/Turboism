package dev.turboism.adapter.cubism.performance;

public final class NativePerformanceProbeBridge {

    private NativePerformanceProbeBridge() { }

    public static long enter(final PerformanceProbeRecorder recorder, final int metricId) {
        try {
            return recorder.enter(PerformanceProbeMetric.byId(metricId));
        } catch (Throwable ignored) {
            recorder.fail();
            return 0L;
        }
    }

    public static void exit(
        final PerformanceProbeRecorder recorder,
        final int metricId,
        final long startedNanos
    ) {
        try {
            recorder.exit(PerformanceProbeMetric.byId(metricId), startedNanos);
        } catch (Throwable ignored) {
            recorder.fail();
        }
    }
}
