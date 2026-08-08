package dev.turboism.bootstrap.carrier;

public interface PerformanceProbeCallback {
    long enter(int metricId);
    void exit(int metricId, long startedNanos);
}
