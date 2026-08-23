package dev.turboism.sdk.hostread;

import java.util.Objects;
import java.util.Optional;

/**
 * The terminal outcome of one asynchronous host read.
 *
 * <p>The compact constructor enforces that the shape matches the status, so callers never have to
 * defend against a contradictory result: {@code SUCCEEDED} carries a value whose type matches the
 * intent and no error, {@code FAILED} carries an error whose code is not {@code CANCELED} and no
 * value, and {@code CANCELED} carries an error whose code is exactly {@code CANCELED}. The
 * non-terminal statuses {@code QUEUED} and {@code RUNNING} are rejected outright — a result only
 * ever describes a settled read.
 *
 * @param intent the intent the read was submitted for
 * @param status the terminal status; never {@code QUEUED} or {@code RUNNING}
 * @param value the read value, present only when the status is {@code SUCCEEDED}
 * @param error the failure detail, present for every status other than {@code SUCCEEDED}
 */
public record AsyncHostReadResult(
    AsyncHostReadIntent intent,
    AsyncHostReadStatus status,
    Optional<AsyncHostReadValue> value,
    Optional<AsyncHostReadError> error
) {
    public AsyncHostReadResult {
        intent = Objects.requireNonNull(intent, "intent");
        status = Objects.requireNonNull(status, "status");
        value = Objects.requireNonNull(value, "value");
        error = Objects.requireNonNull(error, "error");
        final boolean valid = switch (status) {
            case SUCCEEDED -> value.isPresent() && error.isEmpty()
                && compatible(intent, value.orElseThrow());
            case FAILED -> value.isEmpty() && error.isPresent()
                && error.orElseThrow().code() != AsyncHostReadErrorCode.CANCELED;
            case CANCELED -> value.isEmpty() && error.isPresent()
                && error.orElseThrow().code() == AsyncHostReadErrorCode.CANCELED;
            case QUEUED, RUNNING -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("value/error presence does not match result status " + status);
        }
    }

    /**
     * Builds the successful outcome for a completed read.
     *
     * @param intent the intent the read was submitted for
     * @param value the value produced; its concrete type must be the one the intent admits
     * @return a {@code SUCCEEDED} result carrying the value and no error
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the value's type does not match the intent
     */
    public static AsyncHostReadResult success(
        final AsyncHostReadIntent intent,
        final AsyncHostReadValue value
    ) {
        return new AsyncHostReadResult(
            Objects.requireNonNull(intent, "intent"),
            AsyncHostReadStatus.SUCCEEDED,
            Optional.of(Objects.requireNonNull(value, "value")),
            Optional.empty()
        );
    }

    private static boolean compatible(
        final AsyncHostReadIntent intent,
        final AsyncHostReadValue value
    ) {
        return switch (intent) {
            case PROJECT_WORKSPACE_SNAPSHOT -> value instanceof ProjectWorkspaceSnapshot;
        };
    }
}
