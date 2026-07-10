package dev.turboism.adapter.host;

import java.util.Objects;

public record HostSessionFailure(Code code, String message) {
    public HostSessionFailure {
        code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public enum Code {
        SOURCE_FAILED,
        CONNECTION_FAILED,
        CLEANUP_FAILED
    }
}
