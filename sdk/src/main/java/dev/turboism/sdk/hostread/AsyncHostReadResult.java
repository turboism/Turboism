package dev.turboism.sdk.hostread;

import java.util.Objects;
import java.util.Optional;

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
