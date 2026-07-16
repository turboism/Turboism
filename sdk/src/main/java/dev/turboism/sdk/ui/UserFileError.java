package dev.turboism.sdk.ui;

import java.util.Objects;

public record UserFileError(
    UserFileErrorCode code,
    String message
) {
    public UserFileError {
        code = Objects.requireNonNull(code, "code");
        message = UserFileContracts.requireText(message, "message", 1024);
    }
}
