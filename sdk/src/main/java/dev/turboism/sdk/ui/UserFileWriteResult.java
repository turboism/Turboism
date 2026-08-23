package dev.turboism.sdk.ui;

import java.util.Optional;

/**
 * Outcome of an atomic user-file write.
 *
 * @param written {@code true} exactly when the replacement completed; the write
 *                is all-or-nothing, so {@code false} means the target was left
 *                as it was
 * @param error   the failure, present exactly when {@code written} is {@code false}
 */
public record UserFileWriteResult(
    boolean written,
    Optional<UserFileError> error
) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException when {@code written} and the presence of
     *     {@code error} disagree
     * @throws NullPointerException when {@code error} is {@code null}
     */
    public UserFileWriteResult {
        error = UserFileContracts.optional(error, "error");
        if (written == error.isPresent()) {
            throw new IllegalArgumentException(
                "user-file write success/error algebra is invalid"
            );
        }
    }
}
