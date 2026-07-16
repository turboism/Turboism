package dev.turboism.sdk.ui;

import java.util.Optional;

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
