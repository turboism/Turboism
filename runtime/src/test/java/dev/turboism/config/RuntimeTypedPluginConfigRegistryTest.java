package dev.turboism.config;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigSchemaValidationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.RuntimePluginTaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTypedPluginConfigRegistryTest {

    @TempDir
    Path temporary;

    private DisposableScope scope;
    private RuntimeScheduler runtimeScheduler;

    @AfterEach
    void cleanup() throws Exception {
        if (scope != null) {
            scope.close();
        }
        if (runtimeScheduler != null && !runtimeScheduler.isClosed()) {
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void registersMaterializesDefaultsPersistsCasWritesAndSurvivesRestart() throws Exception {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "main",
            "enabled",
            true,
            ConfigCodecs.booleanValue()
        );
        final ConfigSchema schema = new ConfigSchema(
            "main",
            "settings/main.cfg",
            1,
            List.of(enabled)
        );
        RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());
        registry.registerSchema(schema, List.of()).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);

        ConfigReadResult<Boolean> materialized = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(materialized.value().value());
        assertEquals(ConfigValueSource.STORED, materialized.value().source());
        assertEquals(0, materialized.value().revision());
        assertTrue(Files.isRegularFile(
            temporary.resolve("typed-config/settings/main.cfg")
        ));

        var written = registry.write(enabled, false, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertTrue(written.written());
        assertEquals(1, written.revision());

        var conflict = registry.write(enabled, true, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertFalse(conflict.written());
        assertEquals(ConfigErrorCode.REVISION_CONFLICT, conflict.error().orElseThrow().code());
        assertEquals(1, conflict.revision());

        ConfigReadResult<Boolean> stored = registry.read(enabled).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertFalse(stored.value().value());
        assertEquals(ConfigValueSource.STORED, stored.value().source());
        assertEquals(1, stored.value().revision());

        scope.close();
        runtimeScheduler.shutdown();
        scope = null;
        runtimeScheduler = null;

        registry = registry(allPermissions());
        registry.registerSchema(schema, List.of()).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        stored = registry.read(enabled).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(stored.value().value());
        assertEquals(1, stored.value().revision());
    }

    @Test
    void validatesSchemaSynchronouslyBeforePermissionOrPublication() {
        final RuntimeTypedPluginConfigRegistry registry = registry(Set.of());
        final ConfigKey<Boolean> first = new ConfigKey<>(
            "main", "enabled", true, ConfigCodecs.booleanValue()
        );
        final ConfigKey<Boolean> duplicate = new ConfigKey<>(
            "main", "enabled", false, ConfigCodecs.booleanValue()
        );

        final ConfigSchemaValidationException duplicateFailure = assertThrows(
            ConfigSchemaValidationException.class,
            () -> registry.registerSchema(
                new ConfigSchema("main", "main.cfg", 1, List.of(first, duplicate)),
                List.of()
            )
        );
        assertEquals(ConfigSchemaValidationError.DUPLICATE_KEY, duplicateFailure.error());

        final ConfigSchemaValidationException pathFailure = assertThrows(
            ConfigSchemaValidationException.class,
            () -> registry.registerSchema(
                new ConfigSchema("main", "../escape.cfg", 1, List.of(first)),
                List.of()
            )
        );
        assertEquals(ConfigSchemaValidationError.INVALID_PATH, pathFailure.error());

        final ConfigSchemaValidationException gapFailure = assertThrows(
            ConfigSchemaValidationException.class,
            () -> registry.registerSchema(
                new ConfigSchema("main", "main.cfg", 2, List.of(first)),
                List.of()
            )
        );
        assertEquals(ConfigSchemaValidationError.MIGRATION_GAP, gapFailure.error());
    }

    @Test
    void registrationPermissionFailureUsesExactOperationalException() throws Exception {
        final RuntimeTypedPluginConfigRegistry registry = registry(Set.of());
        final ConfigSchema schema = new ConfigSchema(
            "main",
            "main.cfg",
            1,
            List.of(new ConfigKey<>(
                "main", "enabled", true, ConfigCodecs.booleanValue()
            ))
        );

        final ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> registry.registerSchema(schema, List.of()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
        );
        final ConfigRegistrationException cause =
            (ConfigRegistrationException) failure.getCause();
        assertEquals(ConfigRegistrationError.PERMISSION_DENIED, cause.error());
        assertEquals(
            "typed config schema registration failed: PERMISSION_DENIED",
            cause.getMessage()
        );
    }

    @Test
    void typedConfigFailuresUseExactPublicCodesWithoutExposingValuesOrPaths() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimeTypedPluginConfigRegistry registry = registry(Set.of(), failures);
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "private-config", "enabled", true, ConfigCodecs.booleanValue()
        );

        final ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> registry.registerSchema(
                new ConfigSchema(
                    "private-config",
                    "private/settings.cfg",
                    1,
                    List.of(enabled)
                ),
                List.of()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS)
        );

        assertEquals(ConfigRegistrationError.PERMISSION_DENIED,
            ((ConfigRegistrationException) failure.getCause()).error());
        final var collected = failures.snapshot().configFailures();
        assertEquals(1, collected.size());
        assertEquals("PERMISSION_DENIED", collected.get(0).code());
        assertEquals("config.registerSchema", collected.get(0).operationId());
        assertEquals(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE, collected.get(0).permissionId());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("Users"));
    }

    @Test
    void migrationFutureVersionAndMalformedPersistenceFailClosed() throws Exception {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "migrated",
            "enabled",
            false,
            ConfigCodecs.booleanValue()
        );
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(
            temporary.resolve("typed-config")
        );
        store.writeAtomic(
            "migrated.cfg",
            new TypedConfigDocumentStore.StoredDocument(
                1,
                7,
                Map.of("enabled", "true")
            )
        );
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());
        final ConfigMigration migration = new ConfigMigration() {
            @Override public int fromVersion() { return 1; }
            @Override public int toVersion() { return 2; }
            @Override public ConfigDocument migrate(ConfigDocument input) {
                return new ConfigDocument(2, input.encodedValues());
            }
        };
        registry.registerSchema(
            new ConfigSchema("migrated", "migrated.cfg", 2, List.of(enabled)),
            List.of(migration)
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        final ConfigReadResult<Boolean> migrated = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(migrated.value().value());
        assertEquals(ConfigValueSource.STORED, migrated.value().source());
        assertEquals(8, migrated.value().revision());

        store.writeAtomic(
            "migrated.cfg",
            new TypedConfigDocumentStore.StoredDocument(
                3,
                8,
                Map.of("enabled", "false")
            )
        );
        final ConfigReadResult<Boolean> future = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.DEFAULT_FUTURE_VERSION, future.value().source());
        assertEquals(
            ConfigErrorCode.FUTURE_SCHEMA_VERSION,
            future.error().orElseThrow().code()
        );

        final ConfigKey<Boolean> broken = new ConfigKey<>(
            "broken", "enabled", true, ConfigCodecs.booleanValue()
        );
        store.writeAtomic(
            "broken.cfg",
            new TypedConfigDocumentStore.StoredDocument(1, 2, Map.of("enabled", "true"))
        );
        registry.registerSchema(
            new ConfigSchema("broken", "broken.cfg", 2, List.of(broken)),
            List.of(new ConfigMigration() {
                @Override public int fromVersion() { return 1; }
                @Override public int toVersion() { return 2; }
                @Override public ConfigDocument migrate(ConfigDocument input)
                    throws dev.turboism.sdk.config.ConfigMigrationException {
                    throw new dev.turboism.sdk.config.ConfigMigrationException("private migration detail");
                }
            })
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        final ConfigReadResult<Boolean> failedMigration = registry.read(broken)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
            ConfigValueSource.DEFAULT_MIGRATION_FAILED,
            failedMigration.value().source()
        );
        assertEquals(
            ConfigErrorCode.MIGRATION_FAILED,
            failedMigration.error().orElseThrow().code()
        );

        final ConfigKey<Boolean> malformed = new ConfigKey<>(
            "malformed", "enabled", true, ConfigCodecs.booleanValue()
        );
        registry.registerSchema(
            new ConfigSchema("malformed", "malformed.cfg", 1, List.of(malformed)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        Files.write(
            temporary.resolve("typed-config/malformed.cfg"),
            new byte[] {(byte) 0xC3, 0x28}
        );
        final ConfigReadResult<Boolean> malformedResult = registry.read(malformed)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.DEFAULT_UNAVAILABLE, malformedResult.value().source());
        assertEquals(
            ConfigErrorCode.PERSISTENCE_FAILED,
            malformedResult.error().orElseThrow().code()
        );
    }

    @Test
    void closedRegistryReturnsStructuredRuntimeUnavailable() throws Exception {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "closed", "enabled", true, ConfigCodecs.booleanValue()
        );
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());
        registry.registerSchema(
            new ConfigSchema("closed", "closed.cfg", 1, List.of(enabled)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        registry.close();

        final ConfigReadResult<Boolean> read = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.DEFAULT_UNAVAILABLE, read.value().source());
        assertEquals(ConfigErrorCode.RUNTIME_UNAVAILABLE, read.error().orElseThrow().code());
        final var write = registry.write(enabled, false, 0)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(ConfigErrorCode.RUNTIME_UNAVAILABLE, write.error().orElseThrow().code());
    }

    @Test
    void invalidWriteIsStructuredAndLateContinuationUsesPluginExecutor() throws Exception {
        final ConfigKey<Integer> count = new ConfigKey<>(
            "main",
            "count",
            2,
            ConfigCodecs.boundedInt(0, 4)
        );
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());
        registry.registerSchema(
            new ConfigSchema("main", "main.cfg", 1, List.of(count)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        final var invalid = registry.write(count, 9, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertFalse(invalid.written());
        assertEquals(ConfigErrorCode.INVALID_VALUE, invalid.error().orElseThrow().code());

        final var stage = registry.read(count);
        stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
        final AtomicReference<String> thread = new AtomicReference<>();
        final CountDownLatch ran = new CountDownLatch(1);
        stage.thenRun(() -> {
            thread.set(Thread.currentThread().getName());
            ran.countDown();
        });
        assertTrue(ran.await(1, TimeUnit.SECONDS));
        assertTrue(thread.get().contains("plugin.typed-config-test"));
    }

    @Test
    void registrationMaterializesEveryDeclaredDefaultBeforeCompletion() throws Exception {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "widget", "enabled", true, ConfigCodecs.booleanValue()
        );
        final ConfigKey<Integer> count = new ConfigKey<>(
            "widget", "count", 2, ConfigCodecs.boundedInt(0, 4)
        );
        final ConfigKey<List<String>> tags = new ConfigKey<>(
            "widget", "tags", List.of("a"), ConfigCodecs.boundedStringList(8, 16)
        );
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());

        registry.registerSchema(
            new ConfigSchema("widget", "widget/settings.cfg", 1, List.of(enabled, count, tags)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        final Path document = temporary.resolve("typed-config/widget/settings.cfg");
        assertTrue(Files.isRegularFile(document));
        final TypedConfigDocumentStore.StoredDocument stored = new TypedConfigDocumentStore(
            temporary.resolve("typed-config")
        ).read("widget/settings.cfg").orElseThrow();
        assertEquals(1, stored.schemaVersion());
        assertEquals(0, stored.revision());
        assertEquals(
            Map.of("enabled", "true", "count", "2", "tags", "[\"a\"]"),
            stored.encodedValues()
        );

        final ConfigReadResult<Boolean> read = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(read.value().value());
        assertEquals(ConfigValueSource.STORED, read.value().source());
        assertEquals(0, read.value().revision());
        assertTrue(read.error().isEmpty());

        final var written = registry.write(enabled, false, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertTrue(written.written());
        assertEquals(1, written.revision());

        final var conflict = registry.write(enabled, false, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertFalse(conflict.written());
        assertEquals(ConfigErrorCode.REVISION_CONFLICT, conflict.error().orElseThrow().code());
        assertEquals(1, conflict.revision());
    }

    @Test
    void registrationLeavesExistingValidDocumentByteStable() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic(
            "existing.cfg",
            new TypedConfigDocumentStore.StoredDocument(1, 7, Map.of("enabled", "false"))
        );
        final byte[] before = Files.readAllBytes(root.resolve("existing.cfg"));
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "existing", "enabled", true, ConfigCodecs.booleanValue()
        );
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions());

        registry.registerSchema(
            new ConfigSchema("existing", "existing.cfg", 1, List.of(enabled)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertArrayEquals(before, Files.readAllBytes(root.resolve("existing.cfg")));
        final ConfigReadResult<Boolean> read = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(read.value().value());
        assertEquals(ConfigValueSource.STORED, read.value().source());
        assertEquals(7, read.value().revision());
    }

    @Test
    void materializationFailureFailsClosedAndPublishesNothing() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimeTypedPluginConfigRegistry registry = registry(allPermissions(), failures);
        Files.createDirectories(temporary.resolve("typed-config/blocked.cfg"));
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "blocked", "enabled", true, ConfigCodecs.booleanValue()
        );

        final ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> registry.registerSchema(
                new ConfigSchema("blocked", "blocked.cfg", 1, List.of(enabled)),
                List.of()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS)
        );
        assertEquals(
            ConfigRegistrationError.REGISTRATION_FAILED,
            ((ConfigRegistrationException) failure.getCause()).error()
        );
        final var collected = failures.snapshot().configFailures();
        assertEquals(1, collected.size());
        assertEquals("REGISTRATION_FAILED", collected.get(0).code());
        assertEquals("config.registerSchema", collected.get(0).operationId());
        assertEquals(null, collected.get(0).permissionId());
        assertFalse(collected.get(0).message().contains("blocked.cfg"));

        final ConfigReadResult<Boolean> unregistered = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
            ConfigErrorCode.SCHEMA_NOT_REGISTERED,
            unregistered.error().orElseThrow().code()
        );

        Files.delete(temporary.resolve("typed-config/blocked.cfg"));
        registry.registerSchema(
            new ConfigSchema("blocked", "blocked.cfg", 1, List.of(enabled)),
            List.of()
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        final ConfigReadResult<Boolean> stored = registry.read(enabled)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.STORED, stored.value().source());
        assertEquals(0, stored.value().revision());
    }

    private RuntimeTypedPluginConfigRegistry registry(final Set<String> permissions) {
        return registry(permissions, new RuntimeFailureCollector());
    }

    private RuntimeTypedPluginConfigRegistry registry(
        final Set<String> permissions,
        final RuntimeFailureCollector failures
    ) {
        scope = new DisposableScope();
        runtimeScheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(
            "dev.turboism.plugin.typed-config-test",
            runtimeScheduler,
            scope
        );
        return new RuntimeTypedPluginConfigRegistry(
            new NoopLegacyRegistry(),
            "dev.turboism.plugin.typed-config-test",
            temporary.resolve("typed-config"),
            permissions,
            tasks,
            scope,
            new dev.turboism.cleanup.CleanupEvidenceCollector(),
            failures
        );
    }

    private static Set<String> allPermissions() {
        return Set.of(
            PermissionIds.TURBOISM_CONFIG_PLUGIN_READ,
            PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE
        );
    }

    private static final class NoopLegacyRegistry implements PluginConfigRegistry {
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) {
            return Optional.empty();
        }
        @Override public void writeString(String relativePath, String key, String value)
            throws PluginConfigException {
        }
    }
}
