package dev.turboism.config;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigSchema;

import java.util.Map;

/** Immutable schema registration used by the typed config runtime. */
final class RegisteredSchema {
    final ConfigSchema schema;
    final Map<String, ConfigKey<?>> keys;
    final Map<Integer, ConfigMigration> migrations;

    RegisteredSchema(
        final ConfigSchema schema,
        final Map<String, ConfigKey<?>> keys,
        final Map<Integer, ConfigMigration> migrations
    ) {
        this.schema = schema;
        this.keys = keys;
        this.migrations = migrations;
    }
}
