package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Sanitized failure returned from a script execution. */
@PreviewApi
public record ScriptFailure(String code, String message) {

    public ScriptFailure {
        code = Objects.requireNonNull(code, "code").trim();
        message = Objects.requireNonNull(message, "message").trim();
        if (code.isEmpty() || code.length() > 128) {
            throw new IllegalArgumentException("Script failure code must contain 1-128 characters");
        }
        if (message.length() > 2048) {
            throw new IllegalArgumentException("Script failure message is limited to 2048 characters");
        }
    }
}
