package dev.turboism.sdk.config;

import java.util.Objects;

/**
 * A single config failure, reported alongside a fallback value rather than thrown.
 *
 * <p>All three components are mandatory: the compact constructor rejects {@code null} and rejects
 * blank text for {@code message} and {@code key}.
 *
 * @param code the machine-readable classification callers should branch on
 * @param message human-readable detail, never blank
 * @param key the config key the failure is attributed to, never blank
 */
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
