package dev.turboism.sdk.hostread;

import java.time.Duration;
import java.util.Objects;

/**
 * A caller's request for one asynchronous read of host state.
 *
 * <p>The timeout is validated at construction and clamped to a closed range of 100 milliseconds
 * to 10 seconds, so no plugin can pin a host read open indefinitely or busy-spin the service with
 * a near-zero deadline.
 *
 * @param intent the kind of host state being asked for
 * @param timeout the caller's deadline for the read; must be between 100 milliseconds and
 *     10 seconds inclusive
 */
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
