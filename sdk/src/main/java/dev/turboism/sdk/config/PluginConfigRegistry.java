package dev.turboism.sdk.config;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface PluginConfigRegistry {

    Registration readScope(String relativePath);

    Registration writeScope(String relativePath);

    Optional<String> readString(String relativePath, String key);
    void writeString(String relativePath, String key, String value) throws PluginConfigException;

    default CompletionStage<Void> registerSchema(
        final ConfigSchema schema,
        final List<ConfigMigration> migrations
    ) {
        throw new UnsupportedOperationException("typed config schema is not available");
    }

    default <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
        throw new UnsupportedOperationException("typed config read is not available");
    }

    default <T> CompletionStage<ConfigWriteResult> write(
        final ConfigKey<T> key,
        final T value,
        final long expectedRevision
    ) {
        throw new UnsupportedOperationException("typed config write is not available");
    }

    record ConfigScope(String relativePath, String permissionId) {}
}
