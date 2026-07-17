package dev.turboism.sdk.config;

import java.util.Objects;

public record ConfigValue<T>(
    T value,
    ConfigValueSource source,
    long revision
) {
    public ConfigValue {
        value = Objects.requireNonNull(value, "value");
        source = Objects.requireNonNull(source, "source");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
