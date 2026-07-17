package dev.turboism.sdk.ui;

import java.util.Optional;

public record UserFileReadResult<T>(
    Optional<T> value,
    Optional<UserFileError> error,
    boolean truncated
) {
    public UserFileReadResult {
        value = UserFileContracts.optional(value, "value");
        error = UserFileContracts.optional(error, "error");
        if (value.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException(
                "user-file read must contain exactly one of value or error"
            );
        }
        if (error.isPresent() && truncated) {
            throw new IllegalArgumentException(
                "failed user-file read must not be truncated"
            );
        }
    }
}
