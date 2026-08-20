package dev.turboism.sdk.config;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * A plugin's access to its own configuration, both the untyped string API and the typed schema API.
 *
 * <p>Reads and writes are permission-scoped: {@link #readScope} and {@link #writeScope} declare the
 * paths a plugin intends to touch. The typed methods ({@link #registerSchema}, {@link #read},
 * {@link #write}) are optional and default to throwing {@link UnsupportedOperationException}, so an
 * implementation that predates the typed config feature remains valid.
 */
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
