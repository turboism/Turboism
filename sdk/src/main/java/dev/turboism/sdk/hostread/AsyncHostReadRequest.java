package dev.turboism.sdk.hostread;

import java.time.Duration;
import java.util.Objects;

public record AsyncHostReadRequest(
    AsyncHostReadIntent intent,
    Duration timeout
) {
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);

    public AsyncHostReadRequest {
        intent = Objects.requireNonNull(intent, "intent");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be between 100 milliseconds and 10 seconds");
        }
    }
}
