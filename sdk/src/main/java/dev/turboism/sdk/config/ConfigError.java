package dev.turboism.sdk.config;

import java.util.Objects;

public record ConfigError(
    ConfigErrorCode code,
    String message,
    String key
) {
    public ConfigError {
        code = Objects.requireNonNull(code, "code");
        message = requireText(message, "message");
        key = requireText(key, "key");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
