package dev.turboism.sdk.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedPluginConfigContractTest {

    private enum Mode {
        SAFE,
        FAST
    }

    @Test
    void preservesLegacyDescriptorsAndAddsOnlyDefaultTypedMethods() throws Exception {
        assertFalse(PluginConfigRegistry.class.getMethod("readScope", String.class).isDefault());
        assertFalse(PluginConfigRegistry.class.getMethod("writeScope", String.class).isDefault());
        assertFalse(PluginConfigRegistry.class.getMethod("readString", String.class, String.class).isDefault());
        assertFalse(PluginConfigRegistry.class.getMethod(
            "writeString",
            String.class,
            String.class,
            String.class
        ).isDefault());

        final Method register = PluginConfigRegistry.class.getMethod(
            "registerSchema",
            ConfigSchema.class,
            List.class
        );
        final Method read = PluginConfigRegistry.class.getMethod("read", ConfigKey.class);
        final Method write = PluginConfigRegistry.class.getMethod(
            "write",
            ConfigKey.class,
            Object.class,
            long.class
        );
        final Method editable = PluginConfigRegistry.class.getMethod(
            "registerUserEditableSchema",
            ConfigSchema.class,
            List.class,
            ConfigSchemaEditor.class
        );
        assertTrue(register.isDefault());
        assertTrue(editable.isDefault());
        assertTrue(read.isDefault());
        assertTrue(write.isDefault());
        assertEquals(CompletionStage.class, register.getReturnType());
        assertEquals(CompletionStage.class, editable.getReturnType());
    }

    @Test
    void exposesFrozenClosedEnumsWithoutDuplicateKeyRuntimeError() {
        assertEquals(
            "INVALID_SCHEMA,INVALID_CONFIG_ID,INVALID_PATH,INVALID_VERSION,DUPLICATE_CONFIG_ID," +
                "DUPLICATE_PATH,INVALID_KEY,DUPLICATE_KEY,INVALID_CODEC,INVALID_DEFAULT_VALUE," +
                "INVALID_MIGRATION,MIGRATION_GAP,MIGRATION_BRANCH,MIGRATION_CYCLE",
            names(ConfigSchemaValidationError.values())
        );
        assertEquals(
            "PERMISSION_DENIED,RUNTIME_UNAVAILABLE,REGISTRATION_FAILED",
            names(ConfigRegistrationError.values())
        );
        assertEquals(
            "STORED,DEFAULT_MISSING,DEFAULT_INVALID,DEFAULT_FUTURE_VERSION," +
                "DEFAULT_MIGRATION_FAILED,DEFAULT_UNAVAILABLE",
            names(ConfigValueSource.values())
        );
        assertEquals(
            "SCHEMA_NOT_REGISTERED,INVALID_VALUE,FUTURE_SCHEMA_VERSION,MIGRATION_GAP," +
                "MIGRATION_FAILED,REVISION_CONFLICT,PERMISSION_DENIED,PERSISTENCE_FAILED," +
                "RUNTIME_UNAVAILABLE",
            names(ConfigErrorCode.values())
        );
        assertFalse(Arrays.stream(ConfigErrorCode.values())
            .anyMatch(value -> value.name().equals("DUPLICATE_KEY")));
    }

    @Test
    void createsOnlyClosedBuiltInCodecsWithStableTypeIdentity() {
        assertEquals("boolean", ConfigCodecs.booleanValue().typeId());
        assertEquals("int:-5:10", ConfigCodecs.boundedInt(-5, 10).typeId());
        assertEquals(
            "enum:" + Mode.class.getName(),
            ConfigCodecs.enumValue(Mode.class).typeId()
        );
        assertEquals("string-list:3:16", ConfigCodecs.boundedStringList(3, 16).typeId());

        assertThrows(IllegalArgumentException.class, () -> ConfigCodecs.boundedInt(2, 1));
        assertThrows(NullPointerException.class, () -> ConfigCodecs.enumValue(null));
        assertThrows(IllegalArgumentException.class, () -> ConfigCodecs.boundedStringList(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> ConfigCodecs.boundedStringList(1, 0));
    }

    @Test
    void freezesSchemaDocumentAndExceptionShapes() {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "main",
            "enabled",
            true,
            ConfigCodecs.booleanValue()
        );
        final ConfigSchema schema = new ConfigSchema(
            "main",
            "config/main.properties",
            1,
            List.of(enabled)
        );
        assertEquals(List.of(enabled), schema.keys());
        assertThrows(UnsupportedOperationException.class, () -> schema.keys().add(enabled));

        final ConfigDocument document = new ConfigDocument(1, Map.of("enabled", "true"));
        assertEquals("true", document.encodedValues().get("enabled"));
        assertThrows(UnsupportedOperationException.class, () ->
            document.encodedValues().put("other", "false")
        );

        final ConfigSchemaValidationException validation =
            new ConfigSchemaValidationException(ConfigSchemaValidationError.DUPLICATE_KEY);
        assertEquals(ConfigSchemaValidationError.DUPLICATE_KEY, validation.error());
        assertEquals(
            "typed config schema validation failed: DUPLICATE_KEY",
            validation.getMessage()
        );
        final ConfigRegistrationException registration =
            new ConfigRegistrationException(ConfigRegistrationError.PERMISSION_DENIED);
        assertEquals(ConfigRegistrationError.PERMISSION_DENIED, registration.error());
        assertEquals(
            "typed config schema registration failed: PERMISSION_DENIED",
            registration.getMessage()
        );
    }

    @Test
    void enforcesReadAndWriteResultMatrices() {
        final ConfigValue<Boolean> stored = new ConfigValue<>(true, ConfigValueSource.STORED, 2);
        final ConfigError invalid = new ConfigError(
            ConfigErrorCode.INVALID_VALUE,
            "Stored config value is invalid.",
            "enabled"
        );
        final ConfigError conflict = new ConfigError(
            ConfigErrorCode.REVISION_CONFLICT,
            "Config revision did not match.",
            "enabled"
        );

        assertEquals(stored, new ConfigReadResult<>(stored, Optional.empty()).value());
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigReadResult<>(stored, Optional.of(invalid))
        );
        assertTrue(new ConfigReadResult<>(
            new ConfigValue<>(true, ConfigValueSource.DEFAULT_INVALID, 2),
            Optional.of(invalid)
        ).error().isPresent());

        assertTrue(new ConfigWriteResult(true, 3, Optional.empty()).written());
        assertFalse(new ConfigWriteResult(false, 2, Optional.of(conflict)).written());
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigWriteResult(true, 0, Optional.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigWriteResult(false, 2, Optional.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigWriteResult(
                false,
                2,
                Optional.of(new ConfigError(
                    ConfigErrorCode.FUTURE_SCHEMA_VERSION,
                    "Future version.",
                    "enabled"
                ))
            )
        );
    }

    @Test
    void configSchemaEditorIsExplicitImmutableAndBounded() {
        final ConfigSchemaEditor editor = new ConfigSchemaEditor(List.of(
            new ConfigSchemaEditor.Toggle("enabled", "Enabled", OptionalInt.of(20)),
            new ConfigSchemaEditor.Text("name", "Name", 24, OptionalInt.empty()),
            new ConfigSchemaEditor.Choice(
                "mode",
                "Mode",
                List.of(
                    new ConfigSchemaEditor.Option("SAFE", "Safe"),
                    new ConfigSchemaEditor.Option("FAST", "Fast")
                ),
                OptionalInt.of(10)
            )
        ));

        assertEquals(List.of("enabled", "name", "mode"), editor.fields().stream()
            .map(ConfigSchemaEditor.Field::key).toList());
        assertThrows(UnsupportedOperationException.class, () -> editor.fields().clear());
        assertThrows(IllegalArgumentException.class, () -> new ConfigSchemaEditor(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigSchemaEditor(List.of(
            new ConfigSchemaEditor.Toggle("enabled", "One", OptionalInt.empty()),
            new ConfigSchemaEditor.Toggle("enabled", "Two", OptionalInt.empty())
        )));
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigSchemaEditor.Text("name", "Name", 0, OptionalInt.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ConfigSchemaEditor.Toggle("enabled", "Enabled", OptionalInt.of(-1))
        );
        assertThrows(IllegalArgumentException.class, () -> new ConfigSchemaEditor.Choice(
            "mode",
            "Mode",
            List.of(
                new ConfigSchemaEditor.Option("same", "One"),
                new ConfigSchemaEditor.Option("same", "Two")
            ),
            OptionalInt.empty()
        ));
    }

    @Test
    void defaultEditableRegistrationDelegatesToTypedSchemaRegistration() {
        final boolean[] registered = {false};
        final PluginConfigRegistry registry = new PluginConfigRegistry() {
            @Override public dev.turboism.sdk.plugin.Registration readScope(final String path) {
                return () -> { };
            }
            @Override public dev.turboism.sdk.plugin.Registration writeScope(final String path) {
                return () -> { };
            }
            @Override public Optional<String> readString(final String path, final String key) {
                return Optional.empty();
            }
            @Override public void writeString(
                final String path,
                final String key,
                final String value
            ) {
            }
            @Override public CompletionStage<Void> registerSchema(
                final ConfigSchema schema,
                final List<ConfigMigration> migrations
            ) {
                registered[0] = true;
                return CompletableFuture.completedFuture(null);
            }
        };
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "main", "enabled", true, ConfigCodecs.booleanValue()
        );

        registry.registerUserEditableSchema(
            new ConfigSchema("main", "main.cfg", 1, List.of(enabled)),
            List.of(),
            new ConfigSchemaEditor(List.of(
                new ConfigSchemaEditor.Toggle("enabled", "Enabled", OptionalInt.empty())
            ))
        ).toCompletableFuture().join();

        assertTrue(registered[0]);
    }

    private static String names(final Enum<?>[] values) {
        return Arrays.stream(values)
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(","));
    }
}
