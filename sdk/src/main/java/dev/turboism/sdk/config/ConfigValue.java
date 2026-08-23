package dev.turboism.sdk.config;

import java.util.Objects;

/**
 * A config value together with where it came from and the revision it was observed at.
 *
 * <p>The value is never {@code null}: when no usable stored value exists the schema default is
 * carried instead, and {@code source} says why. A negative revision is rejected.
 *
 * @param value the effective value, never {@code null}
 * @param source whether the value was stored or defaulted, and why it was defaulted
 * @param revision the store revision this value was read at, for optimistic-concurrency writes
 * @param <T> the config value type
 */
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
