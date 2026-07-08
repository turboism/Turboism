package dev.turboism.core.runtime;

import java.time.Duration;
import java.util.Objects;

final class PluginCallbackExecutorConfiguration {

    static final PluginCallbackExecutorConfiguration DEFAULT = of(500, 2, 64, 50.0f);

    private final Duration timeoutDuration;
    private final int bulkheadPoolSize;
    private final int queueCapacity;
    private final float circuitBreakerFailureRateThreshold;

    private PluginCallbackExecutorConfiguration(
        Duration timeoutDuration,
        int bulkheadPoolSize,
        int queueCapacity,
        float circuitBreakerFailureRateThreshold
    ) {
        this.timeoutDuration = Objects.requireNonNull(timeoutDuration, "timeoutDuration");
        this.bulkheadPoolSize = requirePositive(bulkheadPoolSize, "bulkheadPoolSize");
        this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        this.circuitBreakerFailureRateThreshold = requireThreshold(
            circuitBreakerFailureRateThreshold,
            "circuitBreakerFailureRateThreshold"
        );
    }

    static PluginCallbackExecutorConfiguration of(
        long timeoutMillis,
        int bulkheadPoolSize,
        int queueCapacity,
        float circuitBreakerFailureRateThreshold
    ) {
        return new PluginCallbackExecutorConfiguration(
            Duration.ofMillis(requirePositiveMillis(timeoutMillis, "timeoutMillis")),
            bulkheadPoolSize,
            queueCapacity,
            circuitBreakerFailureRateThreshold
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
