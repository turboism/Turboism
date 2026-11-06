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
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.persistence.config.StoredDocument;
import dev.turboism.pluginmanagement.PluginCatalogStateStore;
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
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.update.UpdatePreferencesStore;
import dev.turboism.update.UpdateStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays each immutable fixture with the production reader and migration path.
 *
 * <p>Fixtures whose {@code origin} is {@code verified-release-artifact} were produced by the
 * released writer recorded in the manifest. This test never decodes or migrates those bytes
 * itself: it hands the exact fixture bytes to {@link RuntimeConfigRepository},
 * {@link RuntimeTypedPluginConfigRegistry}, {@link RuntimePluginConfigRegistry},
 * {@link PluginCatalogStateStore}, {@link UpdateStateStore} and {@link UpdatePreferencesStore}.
 * Migration policies registered for a typed document mirror the owning plugin's own
 * {@code ConfigMigration} chain (the runtime module deliberately has no dependency on plugin
 * modules); the migration engine, store and recovery-point writer are always the production
 * implementations.
 *
 * <p>{@code pending.json}, {@code installed-build.json}, {@code journal.json} and the launch lease
 * are replayed by the two suites that share their package-private production readers
 * ({@code dev.turboism.pluginmanagement.PluginPersistenceJournalCompatibilityTest} and
 * {@code dev.turboism.update.GoldenUpdateStateSnapshotsTest}). This class refuses any fixture whose
 * family has no owner, so a new family cannot enter the corpus unreplayed.
 */
class PersistenceGoldenSnapshotsTest {

    private static final Set<String> REPLAYED_HERE =
            Set.of("runtime-config", "typed-config", "plugin-properties", "catalog-state", "update-state");
    private static final Set<String> REPLAYED_ELSEWHERE = Set.of("plugin-journal", "installed-build", "update-journal");
    private static final String PROPERTIES_PLUGIN_ID = "dev.turboism.plugin.external-psd-edit";
    private static final byte[] SYNTHETIC_CACHED_CATALOG =
            "{\"note\":\"synthetic-golden-corpus-catalog-not-a-signed-document\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SYNTHETIC_CACHED_SIGNATURE =
            "{\"note\":\"synthetic-golden-corpus-envelope-not-a-signature\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES =
            Path.of(System.getProperty("turboism.goldenSnapshotRoot", "tests/fixtures/snapshots"));
    private static final String LEGACY_DOCUMENT = "legacy.cfg";
    private static final String ATLAS_DOCUMENT = "texture-atlas/layout.cfg";
    private static final String WEBDAV_DOCUMENT = "backup/webdav.cfg";

    @TempDir
    Path temporary;

    @TestFactory
    Stream<DynamicTest> allRegisteredSnapshots() throws Exception {
        final JsonNode cases = JSON.readTree(Files.readAllBytes(FIXTURES.resolve("manifest.json")))
                .path("snapshots");
        assertTrue(cases.isArray());
        assertTrue(cases.size() >= 22, "golden snapshot matrix must never silently shrink");
        final List<JsonNode> fixtures =
                StreamSupport.stream(cases.spliterator(), false).toList();
        for (final JsonNode fixture : fixtures) {
            final String family = fixture.path("family").asText();
            assertTrue(
                    REPLAYED_HERE.contains(family) || REPLAYED_ELSEWHERE.contains(family),
                    "no production replay owns golden family: " + family);
        }
        return fixtures.stream()
                .filter(fixture -> REPLAYED_HERE.contains(fixture.path("family").asText()))
                .map(fixture -> dynamicTest(fixture.path("id").asText(), () -> verify(fixture)));
    }

    private void verify(final JsonNode fixture) throws Exception {
        final String relativePath = fixture.path("path").asText();
        final byte[] original = Files.readAllBytes(FIXTURES.resolve(relativePath));
        final Path home = Files.createTempDirectory(temporary, "golden-");
        switch (fixture.path("family").asText()) {
            case "runtime-config" -> verifyGlobalConfig(home, original, fixture);
            case "typed-config" -> verifyTypedConfig(home, original, fixture);
            case "plugin-properties" -> verifyPluginProperties(home, original, fixture);
            case "catalog-state" -> verifyCatalogState(home, original, fixture);
            case "update-state" -> verifyUpdateState(home, original, fixture);
            default -> throw new AssertionError("Unrecognized golden fixture family: " + relativePath);
        }
    }

    private static void verifyGlobalConfig(final Path home, final byte[] original, final JsonNode fixture)
            throws Exception {
        final String expectation = fixture.path("expectation").asText();
        final Path file = home.resolve("config.json");
        Files.write(file, original);
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});
        final var decoded = repository.read();
        switch (expectation) {
            case "global-config-read-and-update-preserve-user-settings" -> {
                assertEquals(1, decoded.path("schemaVersion").asInt());
                assertEquals("legacy-user", decoded.path("worktreeId").asText());
                assertTrue(decoded.path("safeMode").asBoolean());
                assertEquals(
                        "dev.turboism.plugin.webdav",
                        decoded.path("disabledPlugins").get(0).asText());
                assertFalse(
                        decoded.path("hooks").path("startup").path("skipSplash").asBoolean());
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
                assertFalse(
                        after.path("hooks").path("startup").path("skipSplash").asBoolean());
            }
            case "installer-seed-0.42-reads-as-v1",
                    "installer-seed-0.67.4-reads-as-v1",
                    "runtime-writer-0.42-defaults-read-without-mutation",
                    "runtime-writer-0.67.4-defaults-read-without-mutation" -> {
                assertEquals("turboism.runtime.config", decoded.path("format").asText());
                assertEquals(1, decoded.path("schemaVersion").asInt());
                assertEquals("turboism-runtime", decoded.path("worktreeId").asText());
                assertEquals("plugins", decoded.path("pluginDirs").get(0).asText());
                assertFalse(decoded.path("safeMode").asBoolean());
                assertEquals(
                        "graalvm", decoded.path("launcher").path("cubismJvm").asText());
                if (fixture.path("path").asText().contains("0.67.4")) {
                    assertFalse(decoded.path("useTextIcon").asBoolean());
                    assertTrue(decoded.path("hooks")
                            .path("startup")
                            .path("skipSplash")
                            .asBoolean());
                }
                assertArrayEquals(original, Files.readAllBytes(file), "read must not rewrite released bytes");

                repository.update(config -> {
                    config.put("safeMode", true);
                    return config;
                });
                final var after = new RuntimeConfigRepository(home, ignored -> {}).read();
                assertTrue(after.path("safeMode").asBoolean(), "an update must preserve the fixture's semantics");
                assertEquals("turboism-runtime", after.path("worktreeId").asText());
                assertEquals("plugins", after.path("pluginDirs").get(0).asText());
            }
            default -> throw new AssertionError("Unrecognized golden fixture expectation: " + expectation);
        }
    }

    private void verifyTypedConfig(final Path home, final byte[] original, final JsonNode fixture) throws Exception {
        final String expectation = fixture.path("expectation").asText();
        final String documentPath = fixture.path("documentPath").asText(LEGACY_DOCUMENT);
        final Path configRoot = home.resolve("typed-config");
        Files.createDirectories(configRoot);
        final Path file = configRoot.resolve(documentPath);
        Files.createDirectories(file.getParent());
        Files.write(file, original);

        final TypedShape shape = typedShape(expectation, documentPath);
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 16, ignored -> {}, Clock.systemUTC()),
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
            registry.registerSchema(shape.schema(), shape.migrations())
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            final TypedConfigDocumentStore store = new TypedConfigDocumentStore(configRoot);

            switch (expectation) {
                case "migrate-v1-to-v2-idempotently" -> {
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, first.value().source());
                    assertEquals(Boolean.TRUE, first.value().value());
                    assertEquals(8L, first.value().revision());
                    final var migrated = store.read(documentPath).orElseThrow();
                    assertEquals(2, migrated.schemaVersion());
                    assertEquals(8L, migrated.revision());
                    assertEquals("true", migrated.encodedValues().get("enabled"));
                    final byte[] migratedBytes = Files.readAllBytes(file);
                    // The destructive read-time migration must leave the immutable fixture bytes in
                    // a recovery point, byte for byte, before it replaces the document.
                    final var points = store.recoveryPoints(documentPath);
                    assertEquals(1, points.size(), "a read-time migration must retain one pre-migration image");
                    assertArrayEquals(original, Files.readAllBytes(points.get(0)));
                    final ConfigReadResult<?> repeated =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, repeated.value().source());
                    assertEquals(8L, repeated.value().revision());
                    assertArrayEquals(
                            migratedBytes, Files.readAllBytes(file), "a second read must not repeat the migration");
                }
                case "reject-future-schema-without-overwrite" -> {
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(
                            ConfigValueSource.DEFAULT_FUTURE_VERSION,
                            first.value().source());
                    assertEquals(
                            ConfigErrorCode.FUTURE_SCHEMA_VERSION,
                            first.error().orElseThrow().code());
                    assertArrayEquals(original, Files.readAllBytes(file));
                }
                case "migration-failure-preserves-original-bytes" -> {
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(
                            ConfigValueSource.DEFAULT_MIGRATION_FAILED,
                            first.value().source());
                    assertEquals(
                            ConfigErrorCode.MIGRATION_FAILED,
                            first.error().orElseThrow().code());
                    assertArrayEquals(original, Files.readAllBytes(file));
                }
                case "migrate-v3-to-v4-retain-layout-mode" -> {
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, first.value().source());
                    assertEquals("COMPACT", first.value().value());
                    assertEquals(4L, first.value().revision());
                    final StoredDocument migrated = store.read(documentPath).orElseThrow();
                    assertEquals(4, migrated.schemaVersion());
                    assertEquals(4L, migrated.revision());
                    // The approved v3->v4 rule retains only layout-mode; the legacy algorithm and
                    // parallel keys must be dropped by that migration, never silently re-read.
                    assertEquals(Map.of("layout-mode", "COMPACT"), migrated.encodedValues());
                    final byte[] migratedBytes = Files.readAllBytes(file);
                    final var points = store.recoveryPoints(documentPath);
                    assertEquals(1, points.size(), "a read-time migration must retain one pre-migration image");
                    assertArrayEquals(
                            original,
                            Files.readAllBytes(points.get(0)),
                            "the recovery point must be the released pre-upgrade document byte for byte");
                    final ConfigReadResult<?> repeated =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, repeated.value().source());
                    assertEquals(4L, repeated.value().revision());
                    assertArrayEquals(
                            migratedBytes, Files.readAllBytes(file), "a second read must not repeat the migration");
                    assertEquals(1, store.recoveryPoints(documentPath).size());
                    assertFutureVersionRejected(registry, store, shape.primary(), documentPath, file);
                }
                case "v4-reads-without-migration" -> {
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, first.value().source());
                    assertEquals("COMPACT", first.value().value());
                    assertEquals(4L, first.value().revision());
                    assertArrayEquals(original, Files.readAllBytes(file), "a current document must not be rewritten");
                    assertTrue(store.recoveryPoints(documentPath).isEmpty());
                    assertUnknownKeyRejected(
                            registry,
                            store,
                            shape.primary(),
                            documentPath,
                            file,
                            new StoredDocument(4, 5, Map.of("layout-mode", "COMPACT", "retired-key", "x")));
                    assertFutureVersionRejected(registry, store, shape.primary(), documentPath, file);
                }
                case "v1-webdav-reads-without-migration" -> {
                    assertTypedValue(registry, shape.keys().get(0), true);
                    assertTypedValue(registry, shape.keys().get(1), "https://backup.example.invalid/turboism");
                    assertTypedValue(registry, shape.keys().get(2), "golden-user");
                    assertTypedValue(registry, shape.keys().get(3), "placeholder-not-a-secret");
                    assertTypedValue(registry, shape.keys().get(4), "/turboism-golden");
                    assertTypedValue(registry, shape.keys().get(5), true);
                    assertTypedValue(registry, shape.keys().get(6), 2);
                    assertTypedValue(registry, shape.keys().get(7), 500);
                    assertTypedValue(registry, shape.keys().get(8), 30);
                    assertTypedValue(registry, shape.keys().get(9), "SAVE_TRIGGERED");
                    assertArrayEquals(original, Files.readAllBytes(file), "a v1 document must not be rewritten");
                    assertTrue(store.recoveryPoints(documentPath).isEmpty());
                    assertFutureVersionRejected(registry, store, shape.primary(), documentPath, file);
                }
                case "v1-ui-theme-selection-reads-without-migration" -> {
                    assertTypedValue(registry, shape.primary(), List.of("golden-theme"));
                    final ConfigReadResult<?> first =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(1L, first.value().revision());
                    assertArrayEquals(original, Files.readAllBytes(file), "a v1 document must not be rewritten");
                    assertTrue(store.recoveryPoints(documentPath).isEmpty());
                    assertUnknownKeyRejected(
                            registry,
                            store,
                            shape.primary(),
                            documentPath,
                            file,
                            new StoredDocument(1, 2, Map.of("retired-key", "x")));
                    assertFutureVersionRejected(registry, store, shape.primary(), documentPath, file);
                }
                case "v2-dalsoo-layout-reads-without-migration" -> {
                    assertTypedValue(registry, shape.keys().get(0), TextureAtlasLayoutBackend.DALSOO_POLYGON);
                    assertTypedValue(registry, shape.keys().get(1), TextureAtlasRotationMode.NONE);
                    assertTypedValue(registry, shape.keys().get(2), TextureAtlasLayoutQuality.DENSE);
                    assertTypedValue(registry, shape.keys().get(3), false);
                    assertTypedValue(registry, shape.keys().get(4), 250);
                    assertTypedValue(registry, shape.keys().get(5), false);
                    assertTypedValue(registry, shape.keys().get(6), true);
                    assertTypedValue(registry, shape.keys().get(7), "ANGLE");
                    assertTypedValue(registry, shape.keys().get(8), 75);
                    assertTypedValue(registry, shape.keys().get(9), 8);
                    assertTypedValue(registry, shape.keys().get(10), List.of("golden-model/golden-texture|1|0|1|0"));
                    assertArrayEquals(
                            original, Files.readAllBytes(file), "a current v2 document must not be rewritten");
                    assertTrue(store.recoveryPoints(documentPath).isEmpty());
                    assertUnknownKeyRejected(
                            registry,
                            store,
                            shape.primary(),
                            documentPath,
                            file,
                            new StoredDocument(2, 2, Map.of("backend", "AUTO", "retired-key", "x")));
                    assertFutureVersionRejected(registry, store, shape.primary(), documentPath, file);

                    // No published release ever wrote schema 1 of this document (inventory §14 G5),
                    // so the v1 input is produced here with the production store. The read path must
                    // run the approved v1 -> v2 rule (add the three preset defaults), keep the
                    // pre-upgrade bytes as a recovery point and stay idempotent.
                    store.writeAtomic(documentPath, new StoredDocument(1, 3, Map.of("backend", "HOST_NATIVE")));
                    final byte[] v1Bytes = Files.readAllBytes(file);
                    final ConfigReadResult<?> migrated =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ConfigValueSource.STORED, migrated.value().source());
                    assertEquals(
                            TextureAtlasLayoutBackend.HOST_NATIVE,
                            migrated.value().value());
                    assertEquals(4L, migrated.value().revision());
                    assertEquals(2, store.read(documentPath).orElseThrow().schemaVersion());
                    final var dalsooPoints = store.recoveryPoints(documentPath);
                    assertEquals(1, dalsooPoints.size(), "a read-time migration must retain one pre-migration image");
                    assertArrayEquals(v1Bytes, Files.readAllBytes(dalsooPoints.get(0)));
                    final ConfigReadResult<?> repeated =
                            registry.read(shape.primary()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(4L, repeated.value().revision());
                    assertEquals(1, store.recoveryPoints(documentPath).size());
                }
                default -> throw new AssertionError("Unrecognized golden fixture expectation: " + expectation);
            }
        } finally {
            if (!scheduler.isClosed()) {
                scheduler.shutdown();
            }
        }
    }

    /**
     * Family 3: the released {@code java.util.Properties} writer under the plugin data directory.
     *
     * <p>The released file is read with the production {@link RuntimePluginConfigRegistry}. A key
     * this build no longer owns must survive both the read and a later production write, which is
     * the behaviour the merge-on-write format depends on. Bytes are not stable across writes
     * ({@code Properties.store} stamps a timestamp), so only the read is byte-immutable.</p>
     */
    private void verifyPluginProperties(final Path home, final byte[] original, final JsonNode fixture)
            throws Exception {
        final String documentPath = fixture.path("documentPath").asText();
        final Path dataDir = home.resolve("data").resolve(PROPERTIES_PLUGIN_ID);
        Files.createDirectories(dataDir);
        final Path file = dataDir.resolve(documentPath);
        Files.write(file, original);

        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 16, ignored -> {}, Clock.systemUTC()),
                ignored -> {});
        try (RuntimePluginConfigRegistry registry = new RuntimePluginConfigRegistry(
                        PermissionChecker.allowAll(), scheduler, dataDir, PROPERTIES_PLUGIN_ID);
                Registration readScope = registry.readScope(documentPath)) {
            assertEquals(
                    Optional.of("C:/GoldenCorpus/external-editor.exe"),
                    registry.readString(documentPath, "applicationPath"));
            assertEquals(Optional.of("keep-me"), registry.readString(documentPath, "retired.preference"));
            assertArrayEquals(original, Files.readAllBytes(file), "reading released properties must not rewrite them");

            try (Registration writeScope = registry.writeScope(documentPath)) {
                registry.writeString(documentPath, "applicationPath", "C:/GoldenCorpus/other-editor.exe");
            }
            assertEquals(
                    Optional.of("C:/GoldenCorpus/other-editor.exe"),
                    registry.readString(documentPath, "applicationPath"));
            assertEquals(
                    Optional.of("keep-me"),
                    registry.readString(documentPath, "retired.preference"),
                    "a key this build no longer owns must survive a production write");
        } finally {
            if (!scheduler.isClosed()) {
                scheduler.shutdown();
            }
        }
    }

    /**
     * Family 4: the released {@code catalog-state.json} v2 document, read with the production
     * {@link PluginCatalogStateStore}. The cached payloads are synthetic placeholders, never a real
     * signed catalog or signature.
     */
    private void verifyCatalogState(final Path home, final byte[] original, final JsonNode fixture) throws Exception {
        final Path file = home.resolve(fixture.path("documentPath").asText());
        Files.createDirectories(file.getParent());
        Files.write(file, original);

        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        final PluginCatalogStateStore.State state = store.read();
        assertEquals(OptionalLong.of(42L), state.catalogVersion());
        assertEquals(Optional.of(Instant.parse("2026-09-30T12:00:00Z")), state.publishedAt());
        assertEquals(
                Optional.of("59a36fd75921b6aa47ec76753997c2660f32b583272397733de22704d6e4f169"), state.catalogSha256());
        assertEquals(Optional.of("W/\"golden-corpus-0.67.4\""), state.etag());
        assertEquals(Optional.of(Instant.parse("2026-09-30T12:05:00Z")), state.lastAutomaticAttempt());
        assertArrayEquals(SYNTHETIC_CACHED_CATALOG, state.cachedCatalogBytes().orElseThrow());
        assertArrayEquals(
                SYNTHETIC_CACHED_SIGNATURE, state.cachedSignatureBytes().orElseThrow());
        assertEquals(Map.of("dev.turboism.plugin.example", "0.2.0"), state.stagedUpdates());
        assertArrayEquals(original, Files.readAllBytes(file), "reading a v2 document must not rewrite it");

        // The same v2 document must be writable by the current writer without losing its meaning.
        assertTrue(store.save(state));
        final PluginCatalogStateStore.State rewritten = new PluginCatalogStateStore(home).read();
        assertEquals(state.etag(), rewritten.etag());
        assertEquals(state.catalogVersion(), rewritten.catalogVersion());
        assertEquals(state.publishedAt(), rewritten.publishedAt());
        assertEquals(state.stagedUpdates(), rewritten.stagedUpdates());
        assertArrayEquals(
                SYNTHETIC_CACHED_CATALOG, rewritten.cachedCatalogBytes().orElseThrow());
        assertArrayEquals(
                SYNTHETIC_CACHED_SIGNATURE, rewritten.cachedSignatureBytes().orElseThrow());
    }

    private static void verifyUpdateState(final Path home, final byte[] original, final JsonNode fixture)
            throws Exception {
        switch (fixture.path("expectation").asText()) {
            case "update-state-v1-reads-without-mutation" -> {
                final Path file = home.resolve(UpdateStateStore.FILE_NAME);
                Files.write(file, original);
                final UpdateStateStore.State state = new UpdateStateStore(home).read();
                assertEquals(Optional.of(Instant.parse("2026-08-30T12:00:00Z")), state.lastAutomaticAttempt());
                assertEquals(Optional.of("W/\"golden-corpus-0.44.0\""), state.etag());
                assertEquals(Optional.empty(), state.cachedResult());
                assertArrayEquals(original, Files.readAllBytes(file), "read must not mutate v1 bytes");
            }
            case "update-preferences-v1-reads-without-mutation" -> {
                final Path file = home.resolve(UpdatePreferencesStore.FILE_NAME);
                Files.write(file, original);
                final UpdatePreferencesStore.StoredPreferences preferences = new UpdatePreferencesStore(home).readAll();
                assertFalse(preferences.automaticChecksEnabled());
                assertTrue(
                        preferences.automaticPluginUpdatesEnabled(),
                        "a v0.44.0 document without the plugin field must default to enabled");
                assertArrayEquals(original, Files.readAllBytes(file), "read must not mutate v1 bytes");
            }
            default ->
                throw new AssertionError("Unrecognized golden fixture expectation: "
                        + fixture.path("expectation").asText());
        }
    }

    private static void assertTypedValue(
            final RuntimeTypedPluginConfigRegistry registry, final ConfigKey<?> key, final Object expected)
            throws Exception {
        final ConfigReadResult<?> result =
                registry.read(key).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.STORED, result.value().source());
        assertTrue(result.error().isEmpty());
        assertEquals(expected, result.value().value());
    }

    private static void assertUnknownKeyRejected(
            final RuntimeTypedPluginConfigRegistry registry,
            final TypedConfigDocumentStore store,
            final ConfigKey<?> primary,
            final String documentPath,
            final Path file,
            final StoredDocument retrievableKeyDocument)
            throws Exception {
        store.writeAtomic(documentPath, retrievableKeyDocument);
        final byte[] unknownBytes = Files.readAllBytes(file);
        final ConfigReadResult<?> rejected =
                registry.read(primary).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.DEFAULT_UNAVAILABLE, rejected.value().source());
        assertEquals(
                ConfigErrorCode.PERSISTENCE_FAILED,
                rejected.error().orElseThrow().code());
        assertArrayEquals(unknownBytes, Files.readAllBytes(file));
    }

    private static void assertFutureVersionRejected(
            final RuntimeTypedPluginConfigRegistry registry,
            final TypedConfigDocumentStore store,
            final ConfigKey<?> primary,
            final String documentPath,
            final Path file)
            throws Exception {
        final StoredDocument current = store.read(documentPath).orElseThrow();
        store.writeAtomic(
                documentPath,
                new StoredDocument(current.schemaVersion() + 1, current.revision() + 1, current.encodedValues()));
        final byte[] futureBytes = Files.readAllBytes(file);
        final ConfigReadResult<?> rejected =
                registry.read(primary).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(ConfigValueSource.DEFAULT_FUTURE_VERSION, rejected.value().source());
        assertEquals(
                ConfigErrorCode.FUTURE_SCHEMA_VERSION,
                rejected.error().orElseThrow().code());
        assertArrayEquals(futureBytes, Files.readAllBytes(file), "a future document must be refused without mutation");
    }

    private static TypedShape typedShape(final String expectation, final String documentPath) {
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
        if (List.of(
                        "migrate-v1-to-v2-idempotently",
                        "reject-future-schema-without-overwrite",
                        "migration-failure-preserves-original-bytes")
                .contains(expectation)) {
            return new TypedShape(
                    new ConfigSchema("golden", documentPath, 2, List.of(enabled)),
                    List.of(migration),
                    enabled,
                    List.of(enabled));
        }
        final ConfigKey<String> mode =
                new ConfigKey<>("golden.texture-atlas", "layout-mode", "PART_BUCKET", ConfigCodecs.stringValue(32));
        if (List.of("migrate-v3-to-v4-retain-layout-mode", "v4-reads-without-migration")
                .contains(expectation)) {
            // The production plugin rejects every intermediate document against its final v4
            // schema, so each registered step emits only the surviving layout-mode key. Steps
            // 1->2 and 2->3 are unreachable for these fixtures; the registration policy requires
            // a gap-free chain. The migration engine that executes this chain is production code.
            return new TypedShape(
                    new ConfigSchema("golden.texture-atlas", documentPath, 4, List.of(mode)),
                    List.of(retainOnly(1, 2, mode), retainOnly(2, 3, mode), retainOnly(3, 4, mode)),
                    mode,
                    List.of(mode));
        }
        final String webdav = "golden.webdav";
        if ("v1-ui-theme-selection-reads-without-migration".equals(expectation)) {
            final String uiTheme = "golden.ui-theme";
            final ConfigKey<List<String>> selected =
                    new ConfigKey<>(uiTheme, "selected-theme", List.of(), ConfigCodecs.boundedStringList(1, 128));
            return new TypedShape(
                    new ConfigSchema(uiTheme, documentPath, 1, List.of(selected)),
                    List.of(),
                    selected,
                    List.of(selected));
        }
        if ("v2-dalsoo-layout-reads-without-migration".equals(expectation)) {
            final String dalsoo = "golden.texture-atlas-dalsoo";
            final List<ConfigKey<?>> keys = List.of(
                    new ConfigKey<>(
                            dalsoo,
                            "backend",
                            TextureAtlasLayoutBackend.AUTO,
                            ConfigCodecs.enumValue(TextureAtlasLayoutBackend.class)),
                    new ConfigKey<>(
                            dalsoo,
                            "rotation",
                            TextureAtlasRotationMode.QUARTER,
                            ConfigCodecs.enumValue(TextureAtlasRotationMode.class)),
                    new ConfigKey<>(
                            dalsoo,
                            "quality",
                            TextureAtlasLayoutQuality.BALANCED,
                            ConfigCodecs.enumValue(TextureAtlasLayoutQuality.class)),
                    new ConfigKey<>(dalsoo, "auto-scale", true, ConfigCodecs.booleanValue()),
                    new ConfigKey<>(dalsoo, "fixed-scale-percent", 100, ConfigCodecs.boundedInt(1, 800)),
                    new ConfigKey<>(dalsoo, "use-abey", true, ConfigCodecs.booleanValue()),
                    new ConfigKey<>(dalsoo, "parallel", false, ConfigCodecs.booleanValue()),
                    // The preset enum belongs to the plugin module the runtime must not depend on;
                    // the document stores the constant name, so it mirrors as bounded text.
                    new ConfigKey<>(dalsoo, "lock-preset", "NONE", ConfigCodecs.stringValue(32)),
                    new ConfigKey<>(dalsoo, "auto-scale-tolerance-permille", 0, ConfigCodecs.boundedInt(0, 1000)),
                    new ConfigKey<>(dalsoo, "auto-scale-max-try", 0, ConfigCodecs.boundedInt(0, 64)),
                    new ConfigKey<>(dalsoo, "item-policies", List.of(), ConfigCodecs.boundedStringList(4096, 256)));
            return new TypedShape(
                    new ConfigSchema(dalsoo, documentPath, 2, keys),
                    List.of(dalsooV1ToV2(keys.get(7), keys.get(8), keys.get(9))),
                    keys.get(0),
                    keys);
        }
        final List<ConfigKey<?>> keys = List.of(
                new ConfigKey<>(webdav, "enabled", false, ConfigCodecs.booleanValue()),
                new ConfigKey<>(webdav, "url", "", ConfigCodecs.stringValue(512)),
                new ConfigKey<>(webdav, "username", "", ConfigCodecs.stringValue(256)),
                new ConfigKey<>(webdav, "password", "", ConfigCodecs.stringValue(512)),
                new ConfigKey<>(webdav, "remote.path", "", ConfigCodecs.stringValue(512)),
                new ConfigKey<>(webdav, "verify.tls", true, ConfigCodecs.booleanValue()),
                new ConfigKey<>(webdav, "retry.max", 3, ConfigCodecs.boundedInt(0, 10)),
                new ConfigKey<>(webdav, "retry.base-delay-ms", 500, ConfigCodecs.boundedInt(0, 60_000)),
                new ConfigKey<>(webdav, "timeout.seconds", 30, ConfigCodecs.boundedInt(1, 300)),
                new ConfigKey<>(webdav, "remote.trigger", "SAVE_TRIGGERED", ConfigCodecs.stringValue(32)));
        if ("v1-webdav-reads-without-migration".equals(expectation)) {
            return new TypedShape(new ConfigSchema(webdav, documentPath, 1, keys), List.of(), keys.get(0), keys);
        }
        throw new AssertionError("Unrecognized golden fixture expectation: " + expectation);
    }

    private static ConfigMigration retainOnly(final int from, final int to, final ConfigKey<String> mode) {
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
                final Map<String, String> retained = new java.util.LinkedHashMap<>();
                final String stored = input.encodedValues().get(mode.name());
                if (stored != null) {
                    retained.put(mode.name(), stored);
                }
                return new ConfigDocument(to, retained);
            }
        };
    }

    /**
     * Mirrors the polygon plugin's own {@code V1_TO_V2} step: schema 2 added the lock preset and
     * the two automatic-scale bounds, which default to {@code NONE}/{@code 0}/{@code 0}; every
     * stored value is retained. The migration engine that executes it is production code.
     */
    private static ConfigMigration dalsooV1ToV2(
            final ConfigKey<?> lockPreset, final ConfigKey<?> tolerance, final ConfigKey<?> maxTry) {
        return new ConfigMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public int toVersion() {
                return 2;
            }

            @Override
            public ConfigDocument migrate(final ConfigDocument input) {
                final Map<String, String> retained = new java.util.LinkedHashMap<>(input.encodedValues());
                retained.putIfAbsent(lockPreset.name(), "NONE");
                retained.putIfAbsent(tolerance.name(), "0");
                retained.putIfAbsent(maxTry.name(), "0");
                return new ConfigDocument(2, retained);
            }
        };
    }

    private record TypedShape(
            ConfigSchema schema, List<ConfigMigration> migrations, ConfigKey<?> primary, List<ConfigKey<?>> keys) {}

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
