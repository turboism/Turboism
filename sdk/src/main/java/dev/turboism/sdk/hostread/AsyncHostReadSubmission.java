package dev.turboism.sdk.hostread;

import java.util.Objects;
import java.util.Optional;

public record AsyncHostReadSubmission(
    AsyncHostReadSubmissionStatus status,
    Optional<AsyncHostReadHandle> handle,
    Optional<AsyncHostReadError> error
) {
    public AsyncHostReadSubmission {
        status = Objects.requireNonNull(status, "status");
        handle = Objects.requireNonNull(handle, "handle");
        error = Objects.requireNonNull(error, "error");
        final boolean valid = switch (status) {
            case ACCEPTED, COALESCED -> handle.isPresent() && error.isEmpty();
            case REJECTED -> handle.isEmpty() && error.isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException("handle/error presence does not match submission status " + status);
        }
    }
}
