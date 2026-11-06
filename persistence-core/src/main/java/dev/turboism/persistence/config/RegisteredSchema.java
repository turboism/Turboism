package dev.turboism.persistence.config;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigSchema;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of one schema accepted by {@link TypedConfigSchemaPolicy}.
 *
 * <p>The snapshot is what the runtime actually reads: the original {@link ConfigSchema} with its
 * key list replaced by immutable key defaults, the keys indexed by name and the migration chain
 * indexed by its source version. Callers cannot see or mutate the maps they registered with,
 * so a later change to a plugin's list or enum cannot alter an already registered schema.
 *
 * @param schema the frozen schema declaration, including immutable default values
 * @param keys the declared keys by name, immutable
 * @param migrations the single-step migrations by source version, immutable
 */
public record RegisteredSchema(
        ConfigSchema schema, Map<String, ConfigKey<?>> keys, Map<Integer, ConfigMigration> migrations) {
    public RegisteredSchema {
        Objects.requireNonNull(schema, "schema");
        keys = Map.copyOf(keys);
        migrations = Map.copyOf(migrations);
    }
}
