package dev.turboism.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigMigrationException;
import dev.turboism.sdk.config.ConfigSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the in-memory migration walk and the classified outcomes the runtime maps. */
class TypedConfigMigrationPolicyTest {

    private static final ConfigKey<Boolean> ENABLED =
            new ConfigKey<>("ui", "enabled", false, ConfigCodecs.booleanValue());
    private static final ConfigKey<Integer> COUNT = new ConfigKey<>("ui", "count", 0, ConfigCodecs.boundedInt(0, 10));

    @Test
    void anAlreadyCurrentDocumentIsMigratedWithoutTouchingIt() throws Exception {
        final RegisteredSchema schema = schema(2, List.of(migration(1, 2, ConfigDocument::encodedValues)));

        final TypedConfigMigrationOutcome outcome =
                TypedConfigMigrationPolicy.migrate(schema, 2, Map.of("enabled", "true"));

        assertEquals(TypedConfigMigrationStatus.MIGRATED, outcome.status());
        assertTrue(outcome.migrated());
        assertEquals(Map.of("enabled", "true"), outcome.encodedValues());
    }

    @Test
    void anOlderDocumentWalksEveryStepToTheRegisteredVersion() throws Exception {
        final RegisteredSchema schema = schema(
                3,
                List.of(
                        migration(1, 2, input -> {
                            final Map<String, String> values = new LinkedHashMap<>(input.encodedValues());
                            values.put("count", "3");
                            return values;
                        }),
                        migration(2, 3, input -> {
                            final Map<String, String> values = new LinkedHashMap<>(input.encodedValues());
                            values.put("enabled", "true");
                            return values;
                        })));

        final TypedConfigMigrationOutcome outcome =
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("enabled", "false"));

        assertEquals(TypedConfigMigrationStatus.MIGRATED, outcome.status());
        assertEquals(Map.of("enabled", "true", "count", "3"), outcome.encodedValues());
    }

    @Test
    void aNewerDocumentIsNeverDowngraded() throws Exception {
        final TypedConfigMigrationOutcome outcome =
                TypedConfigMigrationPolicy.migrate(schema(1, List.of()), 2, Map.of("enabled", "true"));

        assertEquals(TypedConfigMigrationStatus.FUTURE_VERSION, outcome.status());
        assertFalse(outcome.migrated());
        assertEquals(Map.of(), outcome.encodedValues());
    }

    @Test
    void aMissingStepIsReportedAsAGap() throws Exception {
        final RegisteredSchema valid = schema(2, List.of(migration(1, 2, ConfigDocument::encodedValues)));
        final RegisteredSchema broken = new RegisteredSchema(valid.schema(), valid.keys(), Map.of());

        final TypedConfigMigrationOutcome outcome =
                TypedConfigMigrationPolicy.migrate(broken, 1, Map.of("enabled", "true"));

        assertEquals(TypedConfigMigrationStatus.MIGRATION_GAP, outcome.status());
    }

    @Test
    void aFailingMigrationIsReportedAsAFailure() throws Exception {
        final RegisteredSchema schema = schema(2, List.of(new ConfigMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public int toVersion() {
                return 2;
            }

            @Override
            public ConfigDocument migrate(final ConfigDocument input) throws ConfigMigrationException {
                throw new ConfigMigrationException("intentional failure");
            }
        }));

        assertEquals(
                TypedConfigMigrationStatus.MIGRATION_FAILED,
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("enabled", "true"))
                        .status());
    }

    @Test
    void aMigrationThatThrowsUncheckedIsAlsoAFailure() throws Exception {
        final RegisteredSchema schema = schema(2, List.of(migration(1, 2, input -> {
            throw new IllegalStateException("intentional failure");
        })));

        assertEquals(
                TypedConfigMigrationStatus.MIGRATION_FAILED,
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("enabled", "true"))
                        .status());
    }

    @Test
    void aMigrationThatDoesNotAdvanceToItsDeclaredVersionIsAFailure() throws Exception {
        final RegisteredSchema schema = schema(2, List.of(migration(1, 2, input -> input.encodedValues())));

        assertEquals(
                TypedConfigMigrationStatus.MIGRATION_FAILED,
                TypedConfigMigrationPolicy.migrate(
                                new RegisteredSchema(
                                        schema.schema(),
                                        schema.keys(),
                                        Map.of(1, new FixedMigration(1, 2, 1, Map.of("enabled", "true")))),
                                1,
                                Map.of("enabled", "true"))
                        .status());
        assertEquals(
                TypedConfigMigrationStatus.MIGRATION_FAILED,
                TypedConfigMigrationPolicy.migrate(
                                new RegisteredSchema(
                                        schema.schema(),
                                        schema.keys(),
                                        Map.of(1, new FixedMigration(1, 2, 3, Map.of("enabled", "true")))),
                                1,
                                Map.of("enabled", "true"))
                        .status());
        // A migration that advances but produces a value the schema cannot decode fails too.
        final RegisteredSchema invalidOutput = new RegisteredSchema(
                schema.schema(), schema.keys(), Map.of(1, new FixedMigration(1, 2, 2, Map.of("enabled", "yes"))));
        assertEquals(
                TypedConfigMigrationStatus.MIGRATION_FAILED,
                TypedConfigMigrationPolicy.migrate(invalidOutput, 1, Map.of("enabled", "true"))
                        .status());
    }

    @Test
    void anUnknownKeyOrUndecodableValueAtTheCurrentVersionIsInvalid() throws Exception {
        final RegisteredSchema schema = schema(1, List.of());

        assertEquals(
                TypedConfigMigrationStatus.INVALID_VALUES,
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("unknown", "true"))
                        .status());
        assertEquals(
                TypedConfigMigrationStatus.INVALID_VALUES,
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("enabled", "TRUE"))
                        .status());
        assertEquals(
                TypedConfigMigrationStatus.INVALID_VALUES,
                TypedConfigMigrationPolicy.migrate(schema, 1, null).status());
        assertEquals(
                TypedConfigMigrationStatus.MIGRATED,
                TypedConfigMigrationPolicy.migrate(schema, 1, Map.of("enabled", "true", "count", "3"))
                        .status());
    }

    private static RegisteredSchema schema(final int version, final List<ConfigMigration> migrations) throws Exception {
        return TypedConfigSchemaPolicy.validate(
                new ConfigSchema("ui", "ui.cfg", version, List.of(ENABLED, COUNT)), migrations);
    }

    private static ConfigMigration migration(
            final int from, final int to, final java.util.function.Function<ConfigDocument, Map<String, String>> step) {
        return new ConfigMigration() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public int toVersion() {
                return to;
            }

            @Override
            public ConfigDocument migrate(final ConfigDocument input) throws ConfigMigrationException {
                return new ConfigDocument(to, step.apply(input));
            }
        };
    }

    private record FixedMigration(int from, int to, int reportedVersion, Map<String, String> values)
            implements ConfigMigration {
        @Override
        public int fromVersion() {
            return from;
        }

        @Override
        public int toVersion() {
            return to;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            return new ConfigDocument(reportedVersion, values);
        }
    }
}
