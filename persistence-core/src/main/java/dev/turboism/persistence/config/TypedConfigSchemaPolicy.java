package dev.turboism.persistence.config;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigSchemaValidationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admission rules for one typed-config schema registration.
 *
 * <p>The policy is pure and total: a schema either produces a {@link RegisteredSchema} snapshot
 * or it fails with the single {@link ConfigSchemaValidationError} that names the first broken
 * rule. Validation runs before anything is read or written, so an illegal registration can
 * never materialize a document, publish settings or half-register a migration chain.
 */
public final class TypedConfigSchemaPolicy {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private TypedConfigSchemaPolicy() {}

    /**
     * Validates one schema declaration and its migration list.
     *
     * <p>Checks, in order: declared keys, config id, portable relative path, positive schema
     * version, key identity and name, duplicate keys, codec recognition, default value fitness,
     * and a single-step, gap-free, branch-free migration chain that ends at the schema version.
     *
     * @param schema the declared schema
     * @param migrations the declared migrations, exactly one per upgraded version
     * @return the immutable snapshot the runtime registers
     * @throws ConfigSchemaValidationException when any rule fails
     */
    public static RegisteredSchema validate(final ConfigSchema schema, final List<ConfigMigration> migrations)
            throws ConfigSchemaValidationException {
        if (schema == null || schema.keys() == null) {
            throw validation(ConfigSchemaValidationError.INVALID_SCHEMA);
        }
        if (!validIdentifier(schema.configId())) {
            throw validation(ConfigSchemaValidationError.INVALID_CONFIG_ID);
        }
        try {
            new StoragePath(StorageRoot.DATA, schema.relativePath());
        } catch (RuntimeException exception) {
            throw validation(ConfigSchemaValidationError.INVALID_PATH);
        }
        if (schema.version() < 1) {
            throw validation(ConfigSchemaValidationError.INVALID_VERSION);
        }
        final Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();
        for (ConfigKey<?> key : schema.keys()) {
            if (key == null || key.configId() == null || key.name() == null) {
                throw validation(ConfigSchemaValidationError.INVALID_KEY);
            }
            if (!schema.configId().equals(key.configId()) || !validIdentifier(key.name())) {
                throw validation(ConfigSchemaValidationError.INVALID_KEY);
            }
            if (keys.putIfAbsent(key.name(), snapshot(key)) != null) {
                throw validation(ConfigSchemaValidationError.DUPLICATE_KEY);
            }
            if (!TypedConfigCodecSupport.isRecognized(key.codec())) {
                throw validation(ConfigSchemaValidationError.INVALID_CODEC);
            }
            if (!TypedConfigCodecSupport.isValidDefault(key)) {
                throw validation(ConfigSchemaValidationError.INVALID_DEFAULT_VALUE);
            }
        }
        if (migrations == null) {
            throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
        }
        final Map<Integer, ConfigMigration> transitions = new HashMap<>();
        for (ConfigMigration migration : migrations) {
            if (migration == null
                    || migration.fromVersion() < 1
                    || migration.toVersion() != migration.fromVersion() + 1
                    || migration.toVersion() > schema.version()) {
                throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
            }
            if (transitions.putIfAbsent(migration.fromVersion(), migration) != null) {
                throw validation(ConfigSchemaValidationError.MIGRATION_BRANCH);
            }
        }
        for (int version = 1; version < schema.version(); version++) {
            if (!transitions.containsKey(version)) {
                throw validation(ConfigSchemaValidationError.MIGRATION_GAP);
            }
        }
        if (transitions.size() != Math.max(0, schema.version() - 1)) {
            throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
        }
        final ConfigSchema snapshot = new ConfigSchema(
                schema.configId(), schema.relativePath(), schema.version(), new ArrayList<>(keys.values()));
        return new RegisteredSchema(snapshot, keys, transitions);
    }

    private static ConfigKey<?> snapshot(final ConfigKey<?> key) {
        return snapshotTypedKey(key);
    }

    private static <T> ConfigKey<T> snapshotTypedKey(final ConfigKey<T> key) {
        @SuppressWarnings("unchecked")
        final T defaultValue = (T) TypedConfigCodecSupport.immutableDefault(key);
        return new ConfigKey<>(key.configId(), key.name(), defaultValue, key.codec());
    }

    private static boolean validIdentifier(final String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static ConfigSchemaValidationException validation(final ConfigSchemaValidationError error) {
        return new ConfigSchemaValidationException(error);
    }
}
