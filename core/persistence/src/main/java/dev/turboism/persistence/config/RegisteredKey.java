package dev.turboism.persistence.config;

import dev.turboism.sdk.config.ConfigKey;
import java.util.Objects;

/**
 * One key resolved inside its registered schema.
 *
 * <p>The lookup that produces it proves the requested key belongs to the schema and carries the
 * same codec identity; the pair exists so a read, write or default materialization can pass the
 * schema and the exact registered key together instead of re-deriving one from the other.
 *
 * @param schema the schema the key was resolved in
 * @param key the registered key, with its immutable default value
 * @param <T> the value type of the key
 */
public record RegisteredKey<T>(RegisteredSchema schema, ConfigKey<T> key) {
    public RegisteredKey {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(key, "key");
    }
}
