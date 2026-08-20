package dev.turboism.sdk.ui;

import java.util.Objects;

/**
 * Structured failure of a user-file operation, returned inside a result rather
 * than thrown.
 *
 * @param code    machine-readable failure classification
 * @param message human-readable detail, non-blank, at most 1024 characters and
 *                free of control characters
 * @throws NullPointerException when {@code code} is {@code null}
 * @throws IllegalArgumentException when {@code message} is blank, too long, or
 *     contains a control character
 */
public record UserFileError(
    UserFileErrorCode code,
    String message
) {
    public UserFileError {
        code = Objects.requireNonNull(code, "code");
        message = UserFileContracts.requireText(message, "message", 1024);
    }
}
