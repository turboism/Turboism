package dev.turboism.sdk.hostread;

import java.util.Objects;

public record AsyncHostReadError(
    AsyncHostReadErrorCode code,
    String message
) {
    public AsyncHostReadError {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 256) {
            throw new IllegalArgumentException("message must be non-blank and at most 256 characters");
        }
    }
}
