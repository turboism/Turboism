package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigMigrationException;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.RuntimePluginTaskScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Replays each immutable fixture with production readers and migration paths. */
class PersistenceGoldenSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES =
            Path.of(System.getProperty("turboism.goldenSnapshotRoot", "tests/fixtures/snapshots"));

    @TempDir
    Path temporary;

    @TestFactory
    Stream<DynamicTest> allRegisteredSnapshots() throws Exception {
        final JsonNode cases = JSON.readTree(Files.readAllBytes(FIXTURES.resolve("manifest.json")))
                .path("snapshots");
        assertTrue(cases.isArray());
        assertTrue(cases.size() >= 4, "golden snapshot matrix must never silently shrink");
        return StreamSupport.stream(cases.spliterator(), false)
                .map(fixture -> dynamicTest(fixture.path("id").asText(), () -> verify(fixture)));
    }

    private void verify(final JsonNode fixture) throws Exception {
        final String relativePath = fixture.path("path").asText();
        final byte[] original = Files.readAllBytes(FIXTURES.resolve(relativePath));
        final Path home = Files.createTempDirectory(temporary, "golden-");
        switch (fixture.path("family").asText()) {
            case "runtime-config" -> verifyGlobalConfig(home, original, fixture);
            case "typed-config" -> verifyTypedConfig(home, original, fixture);
            default -> throw new AssertionError("Unrecognized golden fixture family: " + relativePath);
        }
    }

    private static void verifyGlobalConfig(final Path home, final byte[] original, final JsonNode fixture)
            throws Exception {
        assertEquals(
                "global-config-read-and-update-preserve-user-settings",
                fixture.path("expectation").asText());
        final Path file = home.resolve("config.json");
        Files.write(file, original);
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});
        final var decoded = repository.read();
        assertEquals(1, decoded.path("schemaVersion").asInt());
        assertEquals("legacy-user", decoded.path("worktreeId").asText());
        assertTrue(decoded.path("safeMode").asBoolean());
        assertEquals(
                "dev.turboism.plugin.webdav",
                decoded.path("disabledPlugins").get(0).asText());
        assertFalse(decoded.path("hooks").path("startup").path("skipSplash").asBoolean());
        assertArrayEquals(original, Files.readAllBytes(file), "read must not mutate v1 bytes");

        repository.update(config -> {
            config.put("safeMode", false);
            return config;
        });
        final var after = new RuntimeConfigRepository(home, ignored -> {}).read();
        assertFalse(after.path("safeMode").asBoolean());
        assertEquals("legacy-user", after.path("worktreeId").asText());
        assertEquals(
                "dev.turboism.plugin.webdav",
                after.path("disabledPlugins").get(0).asText());
        assertFalse(after.path("hooks").path("startup").path("skipSplash").asBoolean());
    }

    private static void verifyTypedConfig(final Path home, final byte[] original, final JsonNode fixture)
            throws Exception {
        final Path configRoot = home.resolve("typed-config");
        Files.createDirectories(configRoot);
        final Path file = configRoot.resolve("legacy.cfg");
        Files.write(file, original);
        final String expectation = fixture.path("expectation").asText();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 16, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
        try (DisposableScope scope = new DisposableScope()) {
            final RuntimePluginTaskScheduler tasks =
                    new RuntimePluginTaskScheduler("dev.turboism.plugin.golden-test", scheduler, scope);
            final RuntimeTypedPluginConfigRegistry registry = new RuntimeTypedPluginConfigRegistry(
                    new NoopLegacyRegistry(),
                    "dev.turboism.plugin.golden-test",
                    configRoot,
                    Set.of(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE),
                    tasks,
                    scope);
            final ConfigKey<Boolean> enabled = new ConfigKey<>("golden", "enabled", false, ConfigCodecs.booleanValue());
            final ConfigMigration migration = new ConfigMigration() {
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
                    if ("migration-failure-preserves-original-bytes".equals(expectation)) {
                        throw new ConfigMigrationException("intentional failure fixture");
                    }
                    return new ConfigDocument(2, input.encodedValues());
                }
            };
            registry.registerSchema(new ConfigSchema("golden", "legacy.cfg", 2, List.of(enabled)), List.of(migration))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            final ConfigReadResult<Boolean> first =
                    registry.read(enabled).toCompletableFuture().get(3, TimeUnit.SECONDS);

            switch (expectation) {
                case "migrate-v1-to-v2-idempotently" -> {
                    assertEquals(ConfigValueSource.STORED, first.value().source());
                    assertTrue(first.value().value());
                    assertEquals(8L, first.value().revision());
                    final TypedConfigDocumentStore store = new TypedConfigDocumentStore(configRoot);
                    final var migrated = store.read("legacy.cfg").orElseThrow();
                    assertEquals(2, migrated.schemaVersion());
                    assertEquals(8L, migrated.revision());
                    assertEquals("true", migrated.encodedValues().get("enabled"));
                    final byte[] migratedBytes = Files.readAllBytes(file);
                    // The destructive read-time migration must leave the immutable fixture bytes in
                    // a recovery point, byte for byte, before it replaces the document.
                    final var points = store.recoveryPoints("legacy.cfg");
                    assertEquals(1, points.size(), "a read-time migration must retain one pre-migration image");
                    assertArrayEquals(original, Files.readAllBytes(points.get(0)));
                    final ConfigReadResult<Boolean> repeated =
                            registry.read(enabled).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, repeated.value().source());
                    assertEquals(8L, repeated.value().revision());
                    assertArrayEquals(
                            migratedBytes, Files.readAllBytes(file), "a second read must not repeat the migration");
                }
                case "reject-future-schema-without-overwrite" -> {
                    assertEquals(
                            ConfigValueSource.DEFAULT_FUTURE_VERSION,
                            first.value().source());
                    assertEquals(
                            ConfigErrorCode.FUTURE_SCHEMA_VERSION,
                            first.error().orElseThrow().code());
                    assertArrayEquals(original, Files.readAllBytes(file));
                }
                case "migration-failure-preserves-original-bytes" -> {
                    assertEquals(
                            ConfigValueSource.DEFAULT_MIGRATION_FAILED,
                            first.value().source());
                    assertEquals(
                            ConfigErrorCode.MIGRATION_FAILED,
                            first.error().orElseThrow().code());
                    assertArrayEquals(original, Files.readAllBytes(file));
                }
                default -> throw new AssertionError("Unrecognized golden fixture expectation: " + expectation);
            }
        } finally {
            if (!scheduler.isClosed()) {
                scheduler.shutdown();
            }
        }
    }

    private static final class NoopLegacyRegistry implements PluginConfigRegistry {
        @Override
        public Registration readScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
                throws PluginConfigException {}
    }
}
