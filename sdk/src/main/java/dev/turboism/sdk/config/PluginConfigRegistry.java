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

    /**
     * Registers a typed schema and explicitly opts selected scalar keys into the shared settings UI.
     *
     * <p>The default preserves compatibility with hosts that support typed configuration but do not
     * yet render config editors: the schema is still registered, while the presentation metadata is
     * ignored. Runtimes with editor support validate the metadata before publishing either surface.</p>
     *
     * @param schema typed configuration schema
     * @param migrations complete migration chain for the schema
     * @param editor explicit user-editable field metadata
     * @return completion of schema registration and, when supported, settings publication
     */
    default CompletionStage<Void> registerUserEditableSchema(
        final ConfigSchema schema,
        final List<ConfigMigration> migrations,
        final ConfigSchemaEditor editor
    ) {
        return registerSchema(schema, migrations);
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
