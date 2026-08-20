package dev.turboism.sdk.ui;

import java.util.Optional;

/**
 * Outcome of a {@link UserFileRequest}, with the handle/error pairing enforced
 * by the status.
 *
 * <p>The constructor rejects inconsistent combinations: {@code GRANTED} must
 * carry a handle and no error, {@code CANCELED} neither, {@code DENIED} an
 * error coded {@link UserFileErrorCode#PERMISSION_DENIED}, and
 * {@code UNAVAILABLE} an error coded
 * {@link UserFileErrorCode#RUNTIME_UNAVAILABLE}.</p>
 *
 * @param status how the request resolved
 * @param handle the granted capability, present only for {@code GRANTED}
 * @param error  the failure detail, present only for {@code DENIED} and
 *               {@code UNAVAILABLE}
 * @throws IllegalArgumentException when the combination violates the rules above
 * @throws NullPointerException when any component is {@code null}
 */
public record UserFileRequestResult(
    UserFileRequestStatus status,
    Optional<UserFileHandle> handle,
    Optional<UserFileError> error
) {
    public UserFileRequestResult {
        status = java.util.Objects.requireNonNull(status, "status");
        handle = UserFileContracts.optional(handle, "handle");
        error = UserFileContracts.optional(error, "error");
        final boolean valid = switch (status) {
            case GRANTED -> handle.isPresent() && error.isEmpty();
            case CANCELED -> handle.isEmpty() && error.isEmpty();
            case DENIED -> handle.isEmpty()
                && error.map(UserFileError::code)
                    .filter(code -> code == UserFileErrorCode.PERMISSION_DENIED)
                    .isPresent();
            case UNAVAILABLE -> handle.isEmpty()
                && error.map(UserFileError::code)
                    .filter(code -> code == UserFileErrorCode.RUNTIME_UNAVAILABLE)
                    .isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException("user-file request result algebra is invalid");
        }
    }
}
