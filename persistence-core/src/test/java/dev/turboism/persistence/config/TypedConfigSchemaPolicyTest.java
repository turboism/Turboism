package dev.turboism.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigSchemaValidationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins schema admission and the immutable snapshot the runtime registers. */
class TypedConfigSchemaPolicyTest {

    @Test
    void acceptsAValidSchemaAndFreezesKeyDefaults() throws Exception {
        final ConfigKey<List<String>> values =
                new ConfigKey<>("ui", "values", List.of("a"), ConfigCodecs.boundedStringList(2, 8));
        final ConfigKey<Boolean> enabled = new ConfigKey<>("ui", "enabled", true, ConfigCodecs.booleanValue());

        final RegisteredSchema registered = TypedConfigSchemaPolicy.validate(
                new ConfigSchema("ui", "ui.cfg", 2, List.of(values, enabled)), List.of(migration(1, 2)));

        assertEquals(2, registered.schema().version());
        assertEquals(
                List.of("values", "enabled"),
                registered.schema().keys().stream().map(ConfigKey::name).toList());
        assertEquals(1, registered.migrations().size());
        // The snapshot keeps an immutable copy of the list default so a plugin cannot mutate
        // the registered schema through the value it was handed.
        @SuppressWarnings("unchecked")
        final List<String> snapshotDefault =
                (List<String>) registered.keys().get("values").defaultValue();
        assertEquals(List.of("a"), snapshotDefault);
        assertThrows(UnsupportedOperationException.class, () -> snapshotDefault.add("b"));
    }

    @Test
    void rejectsAMissingSchemaEntryList() {
        assertRejected(
                ConfigSchemaValidationError.INVALID_SCHEMA, new ConfigSchema("ui", "ui.cfg", 1, null), List.of());
        assertRejected(ConfigSchemaValidationError.INVALID_SCHEMA, null, List.of());
    }

    @Test
    void rejectsAnIllegalConfigIdOrPath() {
        assertRejected(
                ConfigSchemaValidationError.INVALID_CONFIG_ID,
                new ConfigSchema("UI", "ui.cfg", 1, List.of(key("UI", "enabled"))),
                List.of());
        assertRejected(
                ConfigSchemaValidationError.INVALID_PATH,
                new ConfigSchema("ui", "../ui.cfg", 1, List.of(key("ui", "enabled"))),
                List.of());
    }

    @Test
    void rejectsAnIllegalVersionKeyOrDefault() {
        assertRejected(
                ConfigSchemaValidationError.INVALID_VERSION,
                new ConfigSchema("ui", "ui.cfg", 0, List.of(key("ui", "enabled"))),
                List.of());
        assertRejected(
                ConfigSchemaValidationError.INVALID_KEY,
                new ConfigSchema(
                        "ui",
                        "ui.cfg",
                        1,
                        List.of(new ConfigKey<>("other", "enabled", true, ConfigCodecs.booleanValue()))),
                List.of());
        assertRejected(
                ConfigSchemaValidationError.DUPLICATE_KEY,
                new ConfigSchema("ui", "ui.cfg", 1, List.of(key("ui", "enabled"), key("ui", "enabled"))),
                List.of());
        assertRejected(
                ConfigSchemaValidationError.INVALID_CODEC,
                new ConfigSchema("ui", "ui.cfg", 1, List.of(new ConfigKey<>("ui", "enabled", true, null))),
                List.of());
        assertRejected(
                ConfigSchemaValidationError.INVALID_DEFAULT_VALUE,
                new ConfigSchema(
                        "ui", "ui.cfg", 1, List.of(new ConfigKey<>("ui", "count", 11, ConfigCodecs.boundedInt(0, 10)))),
                List.of());
    }

    @Test
    void rejectsAGappedBranchedOrMismatchedMigrationChain() {
        assertRejected(
                ConfigSchemaValidationError.INVALID_MIGRATION,
                new ConfigSchema("ui", "ui.cfg", 2, List.of(key("ui", "enabled"))),
                null);
        assertRejected(
                ConfigSchemaValidationError.MIGRATION_GAP,
                new ConfigSchema("ui", "ui.cfg", 3, List.of(key("ui", "enabled"))),
                List.of(migration(1, 2)));
        assertRejected(
                ConfigSchemaValidationError.MIGRATION_GAP,
                new ConfigSchema("ui", "ui.cfg", 3, List.of(key("ui", "enabled"))),
                List.of(migration(2, 3)));
        assertRejected(
                ConfigSchemaValidationError.MIGRATION_BRANCH,
                new ConfigSchema("ui", "ui.cfg", 3, List.of(key("ui", "enabled"))),
                List.of(migration(1, 2), migration(1, 2)));
        assertRejected(
                ConfigSchemaValidationError.INVALID_MIGRATION,
                new ConfigSchema("ui", "ui.cfg", 2, List.of(key("ui", "enabled"))),
                List.of(migration(1, 3)));
    }

    @Test
    void acceptsTheDeclaredKeyListWithoutRetainingTheCallerList() throws Exception {
        final List<ConfigKey<?>> keys = new ArrayList<>(List.of(key("ui", "enabled")));
        final RegisteredSchema registered =
                TypedConfigSchemaPolicy.validate(new ConfigSchema("ui", "ui.cfg", 1, keys), List.of());

        keys.clear();
        assertEquals(1, registered.schema().keys().size());
        assertTrue(registered.migrations().isEmpty());
    }

    private static void assertRejected(
            final ConfigSchemaValidationError expected,
            final ConfigSchema schema,
            final List<ConfigMigration> migrations) {
        final ConfigSchemaValidationException failure = assertThrows(
                ConfigSchemaValidationException.class, () -> TypedConfigSchemaPolicy.validate(schema, migrations));
        assertEquals(expected, failure.error());
    }

    private static ConfigKey<Boolean> key(final String configId, final String name) {
        return new ConfigKey<>(configId, name, true, ConfigCodecs.booleanValue());
    }

    private static ConfigMigration migration(final int from, final int to) {
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
            public ConfigDocument migrate(final ConfigDocument input) {
                return new ConfigDocument(to, input.encodedValues());
            }
        };
    }
}
