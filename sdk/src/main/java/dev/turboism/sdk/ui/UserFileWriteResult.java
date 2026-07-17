package dev.turboism.sdk.ui;

import java.util.Optional;

public record UserFileWriteResult(
    boolean written,
    Optional<UserFileError> error
) {
    public UserFileWriteResult {
        error = UserFileContracts.optional(error, "error");
        if (written == error.isPresent()) {
            throw new IllegalArgumentException(
                "user-file write success/error algebra is invalid"
            );
        }
    }
}
