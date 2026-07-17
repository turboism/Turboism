package dev.turboism.config;

import dev.turboism.sdk.config.ConfigKey;

/** Type-preserving key lookup within a registered typed config schema. */
final class RegisteredKey<T> {
    final RegisteredSchema schema;
    final ConfigKey<T> key;

    RegisteredKey(final RegisteredSchema schema, final ConfigKey<T> key) {
        this.schema = schema;
        this.key = key;
    }
}
