package dev.turboism.persistence.config;

import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigMigrationException;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory upgrade walk for one stored typed-config document.
 *
 * <p>The policy applies the registered single-step migrations until the document reaches the
 * registered schema version. It never writes anything: the caller decides whether a migrated
 * document is stored, which recovery point must exist first and how a failure is reported.
 * A document newer than the schema is never downgraded, and a missing step is a named gap
 * rather than a silent fallback to defaults.
 */
public final class TypedConfigMigrationPolicy {

    private TypedConfigMigrationPolicy() {}

    /**
     * Classifies and, when possible, upgrades one stored document.
     *
     * @param schema the registered schema the document is read for
     * @param storedSchemaVersion the version the stored document was written under
     * @param storedValues the stored encoded values by key name
     * @return the classified outcome; migrated values are only present when the status is
     *     {@link TypedConfigMigrationStatus#MIGRATED}
     */
    public static TypedConfigMigrationOutcome migrate(
            final RegisteredSchema schema, final int storedSchemaVersion, final Map<String, String> storedValues) {
        if (storedSchemaVersion > schema.schema().version()) {
            return failure(TypedConfigMigrationStatus.FUTURE_VERSION);
        }
        ConfigDocument document = new ConfigDocument(storedSchemaVersion, storedValues);
        try {
            while (document.schemaVersion() < schema.schema().version()) {
                final ConfigMigration migration = schema.migrations().get(document.schemaVersion());
                if (migration == null) {
                    return failure(TypedConfigMigrationStatus.MIGRATION_GAP);
                }
                document = Objects.requireNonNull(migration.migrate(document), "migration result");
                if (document.schemaVersion() != migration.toVersion()
                        || !validEncodedValues(schema, document.encodedValues())) {
                    throw new ConfigMigrationException("migration output is invalid");
                }
            }
            if (!validEncodedValues(schema, document.encodedValues())) {
                return failure(TypedConfigMigrationStatus.INVALID_VALUES);
            }
            return new TypedConfigMigrationOutcome(TypedConfigMigrationStatus.MIGRATED, document.encodedValues());
        } catch (ConfigMigrationException | RuntimeException exception) {
            return failure(TypedConfigMigrationStatus.MIGRATION_FAILED);
        }
    }

    private static TypedConfigMigrationOutcome failure(final TypedConfigMigrationStatus status) {
        return new TypedConfigMigrationOutcome(status, Map.of());
    }

    /**
     * Every stored entry must name a registered key and decode under that key's codec; a document
     * that carries an unknown key or an undecodable value is treated as unusable rather than
     * partially applied.
     */
    private static boolean validEncodedValues(final RegisteredSchema schema, final Map<String, String> values) {
        if (values == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            final ConfigKey<?> key = schema.keys().get(entry.getKey());
            if (key == null
                    || entry.getValue() == null
                    || TypedConfigCodecSupport.decode(key, entry.getValue()).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
