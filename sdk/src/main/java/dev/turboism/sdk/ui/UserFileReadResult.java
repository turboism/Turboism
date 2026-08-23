package dev.turboism.sdk.ui;

import java.util.Optional;

/**
 * Outcome of a user-file read, carrying either content or a failure but never
 * both.
 *
 * @param <T>       the decoded content type ({@code String} for UTF-8 reads,
 *                  {@code byte[]} for raw reads)
 * @param value     the content read, empty exactly when {@code error} is present
 * @param error     the failure, empty exactly when {@code value} is present
 * @param truncated {@code true} when the file was longer than the caller’s byte
 *                  budget and only a prefix was returned; always {@code false}
 *                  on failure
 */
public record UserFileReadResult<T>(
    Optional<T> value,
    Optional<UserFileError> error,
    boolean truncated
) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException when both or neither of {@code value} and
     *     {@code error} are present, or a failed read is marked truncated
     * @throws NullPointerException when {@code value} or {@code error} is {@code null}
     */
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
