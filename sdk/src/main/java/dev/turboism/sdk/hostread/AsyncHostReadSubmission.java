package dev.turboism.sdk.hostread;

import java.util.Objects;
import java.util.Optional;

/**
 * The immediate answer to {@link AsyncHostReadService#submit(AsyncHostReadRequest)}.
 *
 * <p>The compact constructor enforces that the shape matches the status: {@code ACCEPTED} and
 * {@code COALESCED} carry a handle and no error, {@code REJECTED} carries an error and no handle.
 * A rejection therefore always explains itself, and an accepted submission always yields something
 * the caller can await.
 *
 * @param status whether the read was accepted, joined to an equivalent in-flight read, or refused
 * @param handle the control surface for the read, present unless the submission was rejected
 * @param error the reason for refusal, present only when the submission was rejected
 */
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
