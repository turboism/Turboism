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

    /**
     * Declares intent to read the config document at {@code relativePath} and returns the scope
     * registration; closing it releases the declaration.
     *
     * @param relativePath plugin-owned config document path
     * @return the scope registration; reads require it to stay open
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the plugin lacks the
     *     config-read permission
     */
    Registration readScope(String relativePath);

    /**
     * Declares intent to write the config document at {@code relativePath} and returns the scope
     * registration; closing it releases the declaration.
     *
     * @param relativePath plugin-owned config document path
     * @return the scope registration; writes require it to stay open
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the plugin lacks the
     *     config-write permission
     */
    Registration writeScope(String relativePath);

    /**
     * Reads one string value from a previously read-scoped document.
     *
     * @param relativePath config document path covered by an open read scope
     * @param key key inside the document
     * @return the stored value, or empty when unset
     * @throws IllegalStateException when no read scope covers {@code relativePath}
     */
    Optional<String> readString(String relativePath, String key);

    /**
     * Stores one string value in a previously write-scoped document.
     *
     * @param relativePath config document path covered by an open write scope
     * @param key key inside the document
     * @param value value to store
     * @throws PluginConfigException when the write is refused or fails
     */
    void writeString(String relativePath, String key, String value) throws PluginConfigException;

    /**
     * Registers a typed configuration schema and its complete migration chain.
     *
     * @param schema typed configuration schema
     * @param migrations complete, unbroken migration chain for the schema
     * @return completion of schema registration
     * @throws UnsupportedOperationException on implementations that predate typed config
     */
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

    /**
     * Reads the typed value bound to {@code key}.
     *
     * @param <T> the key's value type
     * @param key a key declared by a registered schema
     * @return the read result; misses and failures arrive as values, not exceptions
     * @throws UnsupportedOperationException on implementations that predate typed config
     */
    default <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
        throw new UnsupportedOperationException("typed config read is not available");
    }

    /**
     * Writes the typed value bound to {@code key} under optimistic concurrency.
     *
     * @param <T> the key's value type
     * @param key a key declared by a registered schema
     * @param value value to store
     * @param expectedRevision revision observed by an earlier read; a stale token is rejected
     * @return the write result; rejection and failure arrive as values, not exceptions
     * @throws UnsupportedOperationException on implementations that predate typed config
     */
    default <T> CompletionStage<ConfigWriteResult> write(
        final ConfigKey<T> key,
        final T value,
        final long expectedRevision
    ) {
        throw new UnsupportedOperationException("typed config write is not available");
    }

    /** A declared config scope: the document path and the permission that authorized it. */
    record ConfigScope(String relativePath, String permissionId) {}
}
