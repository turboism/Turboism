package dev.turboism.core.runtime.work;

import java.time.Duration;
import java.util.Objects;

final class PluginWorkExecutorConfiguration {

    static final int DEFAULT_LONG_LANE_CONCURRENCY = 2;
    static final long DEFAULT_LONG_RUNNING_THRESHOLD_MILLIS = 30_000L;
    static final long DEFAULT_LONG_RUNNING_REPORT_MILLIS = 300_000L;

    static final PluginWorkExecutorConfiguration DEFAULT = of(500, 2, 64, 50.0f);

    private final Duration timeoutDuration;
    private final int bulkheadPoolSize;
    private final int queueCapacity;
    private final float circuitBreakerFailureRateThreshold;
    private final int longLaneConcurrency;
    private final Duration longRunningThreshold;
    private final Duration longRunningReportInterval;

    private PluginWorkExecutorConfiguration(
        Duration timeoutDuration,
        int bulkheadPoolSize,
        int queueCapacity,
        float circuitBreakerFailureRateThreshold,
        int longLaneConcurrency,
        Duration longRunningThreshold,
        Duration longRunningReportInterval
    ) {
        this.timeoutDuration = Objects.requireNonNull(timeoutDuration, "timeoutDuration");
        this.bulkheadPoolSize = requirePositive(bulkheadPoolSize, "bulkheadPoolSize");
        this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        this.circuitBreakerFailureRateThreshold = requireThreshold(
            circuitBreakerFailureRateThreshold,
            "circuitBreakerFailureRateThreshold"
        );
        this.longLaneConcurrency = requirePositive(longLaneConcurrency, "longLaneConcurrency");
        this.longRunningThreshold = Objects.requireNonNull(
            longRunningThreshold, "longRunningThreshold");
        this.longRunningReportInterval = Objects.requireNonNull(
            longRunningReportInterval, "longRunningReportInterval");
    }

    static PluginWorkExecutorConfiguration of(
        long timeoutMillis,
        int bulkheadPoolSize,
        int queueCapacity,
        float circuitBreakerFailureRateThreshold
    ) {
        return of(
            timeoutMillis,
            bulkheadPoolSize,
            queueCapacity,
            circuitBreakerFailureRateThreshold,
            DEFAULT_LONG_LANE_CONCURRENCY,
            DEFAULT_LONG_RUNNING_THRESHOLD_MILLIS,
            DEFAULT_LONG_RUNNING_REPORT_MILLIS
        );
    }

    static PluginWorkExecutorConfiguration of(
        long timeoutMillis,
        int bulkheadPoolSize,
        int queueCapacity,
        float circuitBreakerFailureRateThreshold,
        int longLaneConcurrency,
        long longRunningThresholdMillis,
        long longRunningReportIntervalMillis
    ) {
        return new PluginWorkExecutorConfiguration(
            Duration.ofMillis(requirePositiveMillis(timeoutMillis, "timeoutMillis")),
            bulkheadPoolSize,
            queueCapacity,
            circuitBreakerFailureRateThreshold,
            longLaneConcurrency,
            Duration.ofMillis(
                requirePositiveMillis(longRunningThresholdMillis, "longRunningThresholdMillis")),
            Duration.ofMillis(
                requirePositiveMillis(longRunningReportIntervalMillis, "longRunningReportIntervalMillis"))
        );
    }

    Duration timeoutDuration() {
        return timeoutDuration;
    }

    int bulkheadPoolSize() {
        return bulkheadPoolSize;
    }

    int queueCapacity() {
        return queueCapacity;
    }

    float circuitBreakerFailureRateThreshold() {
        return circuitBreakerFailureRateThreshold;
    }

    int longLaneConcurrency() {
        return longLaneConcurrency;
    }

    Duration longRunningThreshold() {
        return longRunningThreshold;
    }

    Duration longRunningReportInterval() {
        return longRunningReportInterval;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositiveMillis(long value, String name) {
        if (value < 1L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static float requireThreshold(float value, String name) {
        if (value <= 0.0f || value > 100.0f) {
            throw new IllegalArgumentException(name + " must be greater than 0 and less than or equal to 100");
        }
        return value;
    }
}
