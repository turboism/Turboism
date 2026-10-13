package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.pluginmanagement.PluginCatalogClient.CatalogFetchResult;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.PluginCatalogVerificationException;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.TrustedKey;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.update.UpdatePreferencesStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimePluginAutoUpdaterTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String TEST_KEY_ID = "test-official-v1";

    @TempDir
    Path home;

    @Test
    void bundledProductionKeyVerifiesSignedProductionCatalogAndTamperedSignatureIsRejected() throws Exception {
        final PluginCatalogVerifier productionVerifier = new PluginCatalogVerifier();
        assertTrue(productionVerifier.trustedKeys().containsKey(PluginCatalogVerifier.PRODUCTION_KEY_ID));

        final byte[] prodCatalog =
                "{\"format\":\"turboism.plugin.catalog\",\"schemaVersion\":2,\"catalogVersion\":1,\"publishedAt\":\"2026-08-16T08:48:40Z\",\"plugins\":[]}"
                        .getBytes(StandardCharsets.UTF_8);
        final byte[] prodSig =
                ("{\"format\":\"turboism.plugin.catalog.signature\",\"schemaVersion\":2,\"algorithm\":\"Ed25519\","
                                + "\"keyId\":\"turboism-official-v1\","
                                + "\"catalogSha256\":\"f4e35eb41c2e7299b9e196ee4ffe3a45fded5d841b3306cb35547a09490721e3\","
                                + "\"signature\":\"x12ZrCISS8mUOcPWzyPgJos4rtmS4fd48LN/ZsVle6U08YSg0RUKn5TR8ulQWLlRjHAxCk41TuTfaBeB1BL9DQ==\"}")
                        .getBytes(StandardCharsets.UTF_8);

        final PluginCatalog verified = productionVerifier.verify(prodCatalog, prodSig);
        assertEquals(1L, verified.catalogVersion());
        assertTrue(verified.plugins().isEmpty());

        // Normative step 1: unknown key ID rejected before hashing or parsing catalog bytes.
        final byte[] unparseableCatalog = "not-json".getBytes(StandardCharsets.UTF_8);
        final byte[] unknownKeySig = envelopeBytes(
                "unknown-key-v1",
                sha256Hex(unparseableCatalog),
                Base64.getEncoder().encodeToString(new byte[64]));
        final PluginCatalogVerificationException unknownKey = assertThrows(
                PluginCatalogVerificationException.class,
                () -> productionVerifier.verify(unparseableCatalog, unknownKeySig));
        assertEquals("PLUGIN_CATALOG_UNKNOWN_KEY_ID", unknownKey.code());

        // Normative step 3: catalogSha256 mismatch rejected in constant time before Ed25519 or JSON parsing.
        final byte[] wrongHashSig = envelopeBytes(
                PluginCatalogVerifier.PRODUCTION_KEY_ID,
                "0".repeat(64),
                Base64.getEncoder().encodeToString(new byte[64]));
        final PluginCatalogVerificationException hashMismatch = assertThrows(
                PluginCatalogVerificationException.class,
                () -> productionVerifier.verify(unparseableCatalog, wrongHashSig));
        assertEquals("PLUGIN_CATALOG_HASH_MISMATCH", hashMismatch.code());

        // Normative step 4: tampered Ed25519 signature rejected before JSON parsing.
        final KeyPair otherKey = generateEd25519KeyPair();
        final byte[] forgedSig =
                signEnvelope(unparseableCatalog, otherKey.getPrivate(), PluginCatalogVerifier.PRODUCTION_KEY_ID);
        final PluginCatalogVerificationException badSig = assertThrows(
                PluginCatalogVerificationException.class,
                () -> productionVerifier.verify(unparseableCatalog, forgedSig));
        assertEquals("PLUGIN_CATALOG_SIGNATURE_INVALID", badSig.code());
    }

    @Test
    void downgradeAndReplayAreRejectedViaPersistedCatalogVersion() throws Exception {
        final KeyPair keyPair = generateEd25519KeyPair();
        final PluginCatalogVerifier verifier = testVerifier(keyPair);
        final PluginCatalogStateStore stateStore = new PluginCatalogStateStore(home);

        final byte[] catalogV2 = emptyCatalogBytes(2L, "2026-10-01T00:00:00Z");
        final byte[] sigV2 = signEnvelope(catalogV2, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog verifiedV2 = verifier.verifyAndAdvance(catalogV2, sigV2, stateStore, Optional.of("\"v2\""));
        assertEquals(2L, verifiedV2.catalogVersion());
        assertEquals(2L, stateStore.read().catalogVersion().orElseThrow());

        // Downgrade (v2 -> v1) must be rejected.
        final byte[] catalogV1 = emptyCatalogBytes(1L, "2026-09-01T00:00:00Z");
        final byte[] sigV1 = signEnvelope(catalogV1, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalogVerificationException downgrade = assertThrows(
                PluginCatalogVerificationException.class,
                () -> verifier.verifyAndAdvance(catalogV1, sigV1, stateStore, Optional.of("\"v1\"")));
        assertEquals("PLUGIN_CATALOG_ROLLBACK_REJECTED", downgrade.code());
        assertEquals(2L, stateStore.read().catalogVersion().orElseThrow());

        // Mutated replay with the same catalogVersion=2 must be rejected.
        final byte[] mutatedV2 = emptyCatalogBytes(2L, "2026-10-01T01:00:00Z");
        final byte[] mutatedSigV2 = signEnvelope(mutatedV2, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalogVerificationException mutatedReplay = assertThrows(
                PluginCatalogVerificationException.class,
                () -> verifier.verifyAndAdvance(mutatedV2, mutatedSigV2, stateStore, Optional.empty()));
        assertEquals("PLUGIN_CATALOG_REPLAY_REJECTED", mutatedReplay.code());

        // Exact replay when a strictly newer catalogVersion is required must be rejected.
        final PluginCatalogVerificationException exactReplay = assertThrows(
                PluginCatalogVerificationException.class,
                () -> verifier.verifyStrictlyNewer(catalogV2, sigV2, stateStore, Optional.empty()));
        assertEquals("PLUGIN_CATALOG_REPLAY_REJECTED", exactReplay.code());
    }

    @Test
    void catalogClientEnforcesHttpsIdentityEncodingSizeCapsAndEtagRevalidation() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginCatalogClient(
                        URI.create("http://plugin.turboism.dev/api/v2/catalog.json"),
                        PluginCatalogClient.SIGNATURE_URI,
                        Duration.ofSeconds(5),
                        uri -> {
                            throw new IOException("unreachable");
                        }));

        final Map<URI, StubHttpConnection> connections = new LinkedHashMap<>();
        final StubHttpConnection catalog304 = new StubHttpConnection(PluginCatalogClient.CATALOG_URI, 304, new byte[0]);
        catalog304.responseHeaders.put("ETag", "\"etag-1\"");
        connections.put(PluginCatalogClient.CATALOG_URI, catalog304);

        final PluginCatalogClient client = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                Duration.ofSeconds(5),
                connections::get);
        final CatalogFetchResult result = client.fetchCatalog(Optional.of("\"etag-1\""));
        assertTrue(result instanceof CatalogFetchResult.NotModified);
        assertEquals("identity", catalog304.requestHeaders.get("Accept-Encoding"));
        assertEquals("\"etag-1\"", catalog304.requestHeaders.get("If-None-Match"));

        // Oversized catalog response is rejected.
        final StubHttpConnection oversized = new StubHttpConnection(PluginCatalogClient.CATALOG_URI, 200, new byte[10]);
        oversized.contentLength = PluginCatalogVerifier.MAX_CATALOG_BYTES + 1L;
        connections.put(PluginCatalogClient.CATALOG_URI, oversized);
        assertThrows(IOException.class, () -> client.fetchCatalog(Optional.empty()));

        // Compressed Content-Encoding response is rejected even when Accept-Encoding: identity was sent.
        final StubHttpConnection gzipped =
                new StubHttpConnection(PluginCatalogClient.CATALOG_URI, 200, "{}".getBytes(StandardCharsets.UTF_8));
        gzipped.responseHeaders.put("Content-Encoding", "gzip");
        connections.put(PluginCatalogClient.CATALOG_URI, gzipped);
        assertThrows(IOException.class, () -> client.fetchCatalog(Optional.empty()));
    }

    @Test
    void cubismIncompatibleVersionIsSkippedWithFallbackToHighestCompatibleVersion() throws Exception {
        final String pluginId = "dev.turboism.plugin.mcp";
        installJarAt(home.resolve("plugins/mcp.jar"), pluginId, "0.1.0", true, List.of("5.3.02", "5.3.03"));

        final BuiltArtifact v020 =
                buildArtifact(pluginId, "mcp", "0.2.0", "[0.1.0,0.2.0)", true, List.of("5.3.02", "5.3.03"));
        final BuiltArtifact v030 = buildArtifact(pluginId, "mcp", "0.3.0", "[0.1.0,0.2.0)", true, List.of("5.3.04"));

        final KeyPair keyPair = generateEd25519KeyPair();
        final byte[] catalogBytes = catalogWithReleases(
                1L,
                pluginId,
                "mcp",
                "official",
                List.of(
                        releaseJson(
                                "0.2.0",
                                "stable",
                                "active",
                                "[0.1.0,0.2.0)",
                                true,
                                List.of("5.3.02", "5.3.03"),
                                List.of(),
                                v020),
                        releaseJson(
                                "0.3.0",
                                "stable",
                                "active",
                                "[0.1.0,0.2.0)",
                                true,
                                List.of("5.3.04"),
                                List.of(),
                                v030)));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);

        final PluginCatalog verified = testVerifier(keyPair).verify(catalogBytes, sigBytes);

        // When host Cubism version is unknown and requiresCubism == true, planner fails closed.
        final PluginAutoUpdatePlanner unknownHostPlanner =
                new PluginAutoUpdatePlanner(FrameworkBuildInfo.Channel.STABLE, Optional.empty());
        assertTrue(unknownHostPlanner
                .plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")))
                .isEmpty());

        // When host Cubism version is 5.3.03, 0.3.0 (5.3.04-only) is skipped and planner falls back to 0.2.0.
        final PluginAutoUpdatePlanner host5303Planner =
                new PluginAutoUpdatePlanner(FrameworkBuildInfo.Channel.STABLE, Optional.of("5.3.03"));
        final List<PluginAutoUpdatePlanner.PlannedUpdate> planned =
                host5303Planner.plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")));
        assertEquals(1, planned.size());
        assertEquals("0.2.0", planned.get(0).release().version());

        // Also test fallback when catalog claims 5.3.03 support on 0.3.0, but the downloaded 0.3.0 JAR's
        // embedded cubism-versions.json sidecar only lists 5.3.04!
        final byte[] sidecarMismatchCatalog = catalogWithReleases(
                2L,
                pluginId,
                "mcp",
                "official",
                List.of(
                        releaseJson(
                                "0.2.0",
                                "stable",
                                "active",
                                "[0.1.0,0.2.0)",
                                true,
                                List.of("5.3.02", "5.3.03"),
                                List.of(),
                                v020),
                        releaseJson(
                                "0.3.0",
                                "stable",
                                "active",
                                "[0.1.0,0.2.0)",
                                true,
                                List.of("5.3.03", "5.3.04"),
                                List.of(),
                                v030)));
        final byte[] sidecarMismatchSig = signEnvelope(sidecarMismatchCatalog, keyPair.getPrivate(), TEST_KEY_ID);

        final PluginCatalogClient client = stubClient(
                sidecarMismatchCatalog,
                sidecarMismatchSig,
                Map.of(URI.create(v020.url()), v020.bytes(), URI.create(v030.url()), v030.bytes()));
        final RuntimeScheduler scheduler = scheduler();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimePluginAutoUpdater updater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                client,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.STABLE,
                () -> Optional.of("5.3.03"),
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                diagnostics::add);
        try {
            final PluginAutoUpdateControl.Snapshot snapshot =
                    updater.checkForPluginUpdates().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PluginAutoUpdateControl.Status.UPDATES_STAGED, snapshot.status());
            assertEquals(Map.of(pluginId, "0.2.0"), snapshot.stagedUpdates());
            assertTrue(diagnostics.contains("PLUGIN_UPDATE_CUBISM_SIDECAR_INCOMPATIBLE"));
        } finally {
            updater.close();
            scheduler.shutdown();
        }
    }

    @Test
    void turboismApiIncompatibleYankedAndUnsatisfiedDependencyReleasesAreSkipped() throws Exception {
        final String pluginId = "dev.turboism.plugin.mcp";
        final BuiltArtifact v020 = buildArtifact(pluginId, "mcp", "0.2.0", "[0.1.0,0.2.0)", false, List.of());
        final BuiltArtifact v030 = buildArtifact(pluginId, "mcp", "0.3.0", "[0.2.0,0.3.0)", false, List.of());
        final BuiltArtifact v040 = buildArtifact(pluginId, "mcp", "0.4.0", "[0.1.0,0.2.0)", false, List.of());

        final KeyPair keyPair = generateEd25519KeyPair();
        final byte[] catalogBytes = catalogWithReleases(
                1L,
                pluginId,
                "mcp",
                "official",
                List.of(
                        releaseJson("0.2.0", "stable", "active", "[0.1.0,0.2.0)", false, List.of(), List.of(), v020),
                        // 0.3.0 requires Turboism API [0.2.0,0.3.0) while runtime is 0.1.0
                        releaseJson("0.3.0", "stable", "active", "[0.2.0,0.3.0)", false, List.of(), List.of(), v030),
                        // 0.4.0 is yanked
                        releaseJson("0.4.0", "stable", "yanked", "[0.1.0,0.2.0)", false, List.of(), List.of(), v040)));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog verified = testVerifier(keyPair).verify(catalogBytes, sigBytes);

        final PluginAutoUpdatePlanner planner = new PluginAutoUpdatePlanner(
                FrameworkBuildInfo.Channel.STABLE, Optional.empty(), PluginVersion.parse("0.1.0"));
        final List<PluginAutoUpdatePlanner.PlannedUpdate> planned =
                planner.plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")));
        assertEquals(1, planned.size());
        assertEquals("0.2.0", planned.get(0).release().version());

        // Required dependency check: if 0.2.0 requires missing dependency dev.turboism.plugin.acp [0.2.0,0.3.0), skip
        // it.
        final byte[] depCatalogBytes = catalogWithReleases(
                2L,
                pluginId,
                "mcp",
                "official",
                List.of(releaseJson(
                        "0.2.0",
                        "stable",
                        "active",
                        "[0.1.0,0.2.0)",
                        false,
                        List.of(),
                        List.of(
                                "{\"id\":\"dev.turboism.plugin.acp\",\"version\":\"[0.2.0,0.3.0)\",\"type\":\"required\",\"ordering\":\"before\"}"),
                        v020)));
        final byte[] depSigBytes = signEnvelope(depCatalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog depVerified = testVerifier(keyPair).verify(depCatalogBytes, depSigBytes);
        assertTrue(planner.plan(depVerified, Map.of(pluginId, PluginVersion.parse("0.1.0")))
                .isEmpty());
    }

    @Test
    void disabledPluginIsUpdatedAndMigratedFromInstallerFilenameWhileStayingDisabled() throws Exception {
        final String pluginId = "dev.turboism.plugin.uitheme";
        // Simulate installer-released filename: plugins/ui-theme.jar (not plugins/dev.turboism.plugin.uitheme.jar)
        final Path installerJar = home.resolve("plugins/ui-theme.jar");
        installJarAt(installerJar, pluginId, "0.1.0", false, List.of());

        final RuntimeConfigRepository config = new RuntimeConfigRepository(home, ignored -> {});
        config.setPluginEnabled(pluginId, false);
        assertTrue(config.disabledPlugins().contains(pluginId));

        final BuiltArtifact v020 = buildArtifact(pluginId, "ui-theme", "0.2.0", "[0.1.0,0.2.0)", false, List.of());
        final KeyPair keyPair = generateEd25519KeyPair();
        final byte[] catalogBytes = catalogWithReleases(
                1L,
                pluginId,
                "ui-theme",
                "official",
                List.of(releaseJson("0.2.0", "stable", "active", "[0.1.0,0.2.0)", false, List.of(), List.of(), v020)));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);

        final PluginCatalogClient client =
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(v020.url()), v020.bytes()));
        final RuntimeScheduler scheduler = scheduler();
        final RuntimePluginManagementService management =
                new RuntimePluginManagementService(home, Optional::empty, List::of);
        final RuntimePluginAutoUpdater updater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                client,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        management.bindAutoUpdater(updater);
        try {
            final PluginAutoUpdateControl.Snapshot snapshot =
                    management.checkForPluginUpdates().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PluginAutoUpdateControl.Status.UPDATES_STAGED, snapshot.status());
            assertTrue(snapshot.reminder());

            // Before restart: Pending column shows UPDATE (0.2.0) and desiredState remains DISABLED.
            final CorePluginManagement.PluginInfo beforeApply = management.plugins().stream()
                    .filter(p -> p.id().equals(pluginId))
                    .findFirst()
                    .orElseThrow();
            assertEquals("0.1.0", beforeApply.version());
            assertEquals("DISABLED", beforeApply.desiredState());
            assertEquals(Optional.of("UPDATE (0.2.0)"), beforeApply.pendingOperation());
            assertTrue(config.disabledPlugins().contains(pluginId));

            // Apply at next startup: migrates plugins/ui-theme.jar -> plugins/dev.turboism.plugin.uitheme.jar with no
            // duplicates!
            final PendingPluginOperations.ApplyResult applied = RuntimePluginManagementService.applyPending(home);
            assertTrue(applied.applied(), applied.code());
            assertFalse(Files.exists(installerJar), "old installer filename must be removed");
            final Path canonicalJar = home.resolve("plugins/" + pluginId + ".jar");
            assertTrue(Files.isRegularFile(canonicalJar), "canonical pluginId.jar must exist");
            assertArrayEquals(v020.bytes(), Files.readAllBytes(canonicalJar));

            // Verify disabledPlugins was never modified and plugin stays DISABLED with version 0.2.0.
            assertTrue(config.disabledPlugins().contains(pluginId));
            final CorePluginManagement.PluginInfo afterApply = management.plugins().stream()
                    .filter(p -> p.id().equals(pluginId))
                    .findFirst()
                    .orElseThrow();
            assertEquals("0.2.0", afterApply.version());
            assertEquals("DISABLED", afterApply.desiredState());
            assertEquals(Optional.empty(), afterApply.pendingOperation());
        } finally {
            management.close();
            scheduler.shutdown();
        }
    }

    @Test
    void sizeAndHashMismatchAndStartupRollbackPreserveExistingPluginDirectory() throws Exception {
        final String pluginId = "dev.turboism.plugin.mcp";
        final Path existingJar = home.resolve("plugins/" + pluginId + ".jar");
        final byte[] originalBytes = installJarAt(existingJar, pluginId, "0.1.0", false, List.of());

        final BuiltArtifact v020 = buildArtifact(pluginId, "mcp", "0.2.0", "[0.1.0,0.2.0)", false, List.of());
        final KeyPair keyPair = generateEd25519KeyPair();
        final byte[] catalogBytes = catalogWithReleases(
                1L,
                pluginId,
                "mcp",
                "official",
                List.of(releaseJson("0.2.0", "stable", "active", "[0.1.0,0.2.0)", false, List.of(), List.of(), v020)));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);

        // 1. Tampered artifact bytes (same size, wrong SHA-256) must be rejected and not staged.
        final byte[] tamperedJar = v020.bytes().clone();
        tamperedJar[tamperedJar.length - 1] ^= 0x01;
        final PluginCatalogClient badHashClient =
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(v020.url()), tamperedJar));
        final RuntimeScheduler scheduler = scheduler();
        final RuntimePluginAutoUpdater badHashUpdater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                badHashClient,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        try {
            final PluginAutoUpdateControl.Snapshot res =
                    badHashUpdater.checkForPluginUpdates().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PluginAutoUpdateControl.Status.UP_TO_DATE, res.status());
            assertFalse(Files.exists(home.resolve("state/runtime/plugin-management/pending.json")));
        } finally {
            badHashUpdater.close();
        }

        // 2. Truncated artifact bytes (size mismatch) must be rejected and not staged.
        final byte[] truncatedJar = new byte[(int) v020.size() - 5];
        System.arraycopy(v020.bytes(), 0, truncatedJar, 0, truncatedJar.length);
        final PluginCatalogClient badSizeClient =
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(v020.url()), truncatedJar));
        final RuntimePluginAutoUpdater badSizeUpdater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                badSizeClient,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        try {
            final PluginAutoUpdateControl.Snapshot res =
                    badSizeUpdater.checkForPluginUpdates().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PluginAutoUpdateControl.Status.UP_TO_DATE, res.status());
            assertFalse(Files.exists(home.resolve("state/runtime/plugin-management/pending.json")));
        } finally {
            badSizeUpdater.close();
        }

        // 3. Valid staging followed by corrupted staged package on disk before startup triggers whole-directory
        // rollback.
        final byte[] catalogV2 = catalogWithReleases(
                2L,
                pluginId,
                "mcp",
                "official",
                List.of(releaseJson("0.2.0", "stable", "active", "[0.1.0,0.2.0)", false, List.of(), List.of(), v020)));
        final byte[] sigV2 = signEnvelope(catalogV2, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalogClient validClient =
                stubClient(catalogV2, sigV2, Map.of(URI.create(v020.url()), v020.bytes()));
        final RuntimePluginAutoUpdater validUpdater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                validClient,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        try {
            final PluginAutoUpdateControl.Snapshot staged =
                    validUpdater.checkForPluginUpdates().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PluginAutoUpdateControl.Status.UPDATES_STAGED, staged.status());
            // Tamper with the staged package file in state/runtime/plugin-management/packages/ before apply()
            try (var packages = Files.list(home.resolve("state/runtime/plugin-management/packages"))) {
                for (Path pkg : packages.toList()) {
                    Files.writeString(pkg, "corrupted-before-startup");
                }
            }
            final PendingPluginOperations.ApplyResult rolledBack = RuntimePluginManagementService.applyPending(home);
            assertEquals(PendingPluginOperations.Status.ROLLED_BACK, rolledBack.status());
            assertArrayEquals(originalBytes, Files.readAllBytes(existingJar));
        } finally {
            validUpdater.close();
            scheduler.shutdown();
        }
    }

    @Test
    void optOutAndSafeModePreventAutomaticPluginUpdatesAndChannelPolicyAcceptsPreviewOnBetaAndNightly()
            throws Exception {
        final String pluginId = "dev.turboism.plugin.mcp";
        installJarAt(home.resolve("plugins/" + pluginId + ".jar"), pluginId, "0.1.0", false, List.of());

        final BuiltArtifact preview020 = buildArtifact(pluginId, "mcp", "0.2.0", "[0.1.0,0.2.0)", false, List.of());
        final KeyPair keyPair = generateEd25519KeyPair();
        final byte[] catalogBytes = catalogWithReleases(
                1L,
                pluginId,
                "mcp",
                "official",
                List.of(releaseJson(
                        "0.2.0", "preview", "active", "[0.1.0,0.2.0)", false, List.of(), List.of(), preview020)));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog verified = testVerifier(keyPair).verify(catalogBytes, sigBytes);

        // Channel policy: stable skips preview; beta and nightly accept preview.
        assertTrue(new PluginAutoUpdatePlanner(FrameworkBuildInfo.Channel.STABLE, Optional.empty())
                .plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")))
                .isEmpty());
        assertEquals(
                1,
                new PluginAutoUpdatePlanner(FrameworkBuildInfo.Channel.BETA, Optional.empty())
                        .plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")))
                        .size());
        assertEquals(
                1,
                new PluginAutoUpdatePlanner(FrameworkBuildInfo.Channel.NIGHTLY, Optional.empty())
                        .plan(verified, Map.of(pluginId, PluginVersion.parse("0.1.0")))
                        .size());

        // Safe mode disables automatic and manual updates completely without network traffic.
        final int[] networkCalls = {0};
        final PluginCatalogClient countingClient = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI, PluginCatalogClient.SIGNATURE_URI, Duration.ofSeconds(5), uri -> {
                    networkCalls[0]++;
                    throw new IOException("unexpected network call");
                });
        final RuntimeScheduler scheduler = scheduler();
        final RuntimePluginAutoUpdater safeModeUpdater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(true),
                countingClient,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.BETA,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ZERO,
                Duration.ofDays(1),
                ignored -> {});
        try {
            safeModeUpdater.startPluginAutoUpdater();
            assertEquals(
                    PluginAutoUpdateControl.Status.UNAVAILABLE,
                    safeModeUpdater.pluginUpdateSnapshot().status());
            assertEquals(
                    PluginAutoUpdateControl.Status.UNAVAILABLE,
                    safeModeUpdater
                            .checkForPluginUpdates()
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
            assertEquals(0, networkCalls[0]);
        } finally {
            safeModeUpdater.close();
        }

        // Opt-out switch disables automatic background checks and persists in update-preferences.json.
        final RuntimePluginAutoUpdater optOutUpdater = new RuntimePluginAutoUpdater(
                home,
                scheduler,
                settings(false),
                countingClient,
                testVerifier(keyPair),
                Clock.fixed(NOW, ZoneOffset.UTC),
                FrameworkBuildInfo.Channel.BETA,
                Optional::empty,
                PluginVersion.parse("0.1.0"),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        try {
            assertTrue(optOutUpdater.automaticPluginUpdatesEnabled());
            assertTrue(optOutUpdater.saveAutomaticPluginUpdatesEnabled(false).saved());
            assertFalse(optOutUpdater.automaticPluginUpdatesEnabled());
            assertFalse(new UpdatePreferencesStore(home).automaticPluginUpdatesEnabled());
            optOutUpdater.startPluginAutoUpdater();
            assertEquals(
                    PluginAutoUpdateControl.Status.DISABLED,
                    optOutUpdater.pluginUpdateSnapshot().status());
            assertEquals(0, networkCalls[0]);
        } finally {
            optOutUpdater.close();
            scheduler.shutdown();
        }
    }

    private static KeyPair generateEd25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static PluginCatalogVerifier testVerifier(final KeyPair keyPair) {
        final String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return new PluginCatalogVerifier(
                Map.of(TEST_KEY_ID, new TrustedKey(TEST_KEY_ID, pem, "test", keyPair.getPublic())), false);
    }

    private static byte[] emptyCatalogBytes(final long catalogVersion, final String publishedAt) {
        return ("{\"format\":\"turboism.plugin.catalog\",\"schemaVersion\":2,\"catalogVersion\":"
                        + catalogVersion
                        + ",\"publishedAt\":\""
                        + publishedAt
                        + "\",\"plugins\":[]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] catalogWithReleases(
            final long catalogVersion,
            final String pluginId,
            final String slug,
            final String trust,
            final List<String> releasesJson) {
        return ("{\"format\":\"turboism.plugin.catalog\",\"schemaVersion\":2,\"catalogVersion\":"
                        + catalogVersion
                        + ",\"publishedAt\":\"2026-10-01T00:00:00Z\",\"plugins\":[{"
                        + "\"id\":\"" + pluginId + "\","
                        + "\"slug\":\"" + slug + "\","
                        + "\"name\":\"Example Official Plugin\","
                        + "\"summary\":\"Official summary\","
                        + "\"trust\":\"" + trust + "\","
                        + "\"author\":\"Turboism Contributors\","
                        + "\"license\":\"Project License\","
                        + "\"repository\":\"https://github.com/turboism/Turboism\","
                        + "\"support\":\"https://github.com/turboism/Turboism/issues\","
                        + "\"localizations\":{\"zh-Hans\":{\"name\":\"示例\",\"summary\":\"摘要\"},\"ko\":{\"name\":\"예시\",\"summary\":\"요약\"}},"
                        + "\"releases\":[" + String.join(",", releasesJson) + "]"
                        + "}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String releaseJson(
            final String version,
            final String channel,
            final String status,
            final String turboismApi,
            final boolean requiresCubism,
            final List<String> cubismVersions,
            final List<String> dependenciesJson,
            final BuiltArtifact artifact) {
        final String cubismList = cubismVersions.stream()
                .map(v -> "\"" + v + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{"
                + "\"version\":\"" + version + "\","
                + "\"channel\":\"" + channel + "\","
                + "\"status\":\"" + status + "\","
                + "\"publishedAt\":\"2026-10-01T00:00:00Z\","
                + "\"category\":\"integration\","
                + "\"tags\":[\"official\"],"
                + "\"turboismApi\":\"" + turboismApi + "\","
                + "\"requiresCubism\":" + requiresCubism + ","
                + "\"cubismVersions\":[" + cubismList + "],"
                + "\"platforms\":[\"windows-x64\"],"
                + "\"dependencies\":[" + String.join(",", dependenciesJson) + "],"
                + "\"permissions\":[],"
                + "\"releaseUrl\":\"https://github.com/turboism/turboism-plugin-directory/releases/tag/plugin-"
                + version + "\","
                + "\"artifact\":{"
                + "\"mediaType\":\"application/java-archive\","
                + "\"fileName\":\"" + artifact.fileName() + "\","
                + "\"url\":\"" + artifact.url() + "\","
                + "\"sha256\":\"" + artifact.sha256() + "\","
                + "\"descriptorSha256\":\"" + artifact.descriptorSha256() + "\","
                + "\"size\":" + artifact.size()
                + "}}";
    }

    private static byte[] signEnvelope(final byte[] catalogBytes, final PrivateKey privateKey, final String keyId)
            throws Exception {
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(catalogBytes);
        final String signatureBase64 = Base64.getEncoder().encodeToString(signer.sign());
        return envelopeBytes(keyId, sha256Hex(catalogBytes), signatureBase64);
    }

    private static byte[] envelopeBytes(final String keyId, final String catalogSha256, final String signatureBase64) {
        return ("{\"format\":\"turboism.plugin.catalog.signature\",\"schemaVersion\":2,\"algorithm\":\"Ed25519\","
                        + "\"keyId\":\"" + keyId + "\","
                        + "\"catalogSha256\":\"" + catalogSha256 + "\","
                        + "\"signature\":\"" + signatureBase64 + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] installJarAt(
            final Path target,
            final String id,
            final String version,
            final boolean requiresCubism,
            final List<String> supportedCubism)
            throws Exception {
        Files.createDirectories(target.getParent());
        final BuiltArtifact built = buildArtifact(id, "mod", version, "[0.1.0,0.2.0)", requiresCubism, supportedCubism);
        Files.write(target, built.bytes());
        return built.bytes();
    }

    private static BuiltArtifact buildArtifact(
            final String id,
            final String slug,
            final String version,
            final String turboismApi,
            final boolean requiresCubism,
            final List<String> supportedCubism)
            throws Exception {
        final String entrypoint = "example.Plugin";
        final String descriptor = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":3,\"id\":\"" + id
                + "\",\"name\":\"Example\",\"version\":\"" + version + "\",\"description\":\"Example\","
                + "\"entrypoints\":[\"" + entrypoint + "\"],\"turboismApi\":\"" + turboismApi + "\","
                + "\"authors\":[{\"name\":\"Turboism\"}],\"license\":\"Project License\","
                + "\"website\":\"https://turboism.dev\",\"resources\":[],"
                + "\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},"
                + "\"dependencies\":[],\"permissions\":[],\"capabilities\":[],"
                + "\"environment\":{\"requiresCubism\":" + requiresCubism + ",\"ui\":\"none\"},"
                + "\"category\":\"integration\",\"tags\":[\"official\"]}";
        final byte[] descriptorBytes = descriptor.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            putEntry(jar, "META-INF/turboism/plugin.json", descriptorBytes);
            putEntry(
                    jar,
                    "META-INF/turboism/i18n/messages.properties",
                    "plugin.name=Example\nplugin.description=Example\n".getBytes(StandardCharsets.UTF_8));
            putEntry(jar, entrypoint.replace('.', '/') + ".class", new byte[] {0});
            if (requiresCubism) {
                final String versionsJson = supportedCubism.stream()
                        .map(v -> "\"" + v + "\"")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                final String sidecar = "{\"format\":\"turboism.plugin-cubism-versions/v1\",\"plugin\":\"" + id
                        + "\",\"supportedVersions\":[" + versionsJson + "],\"fullFeatureVersions\":["
                        + versionsJson + "]}";
                putEntry(jar, "META-INF/turboism/cubism-versions.json", sidecar.getBytes(StandardCharsets.UTF_8));
            }
        }
        final byte[] jarBytes = out.toByteArray();
        final String fileName = slug + "-" + version + ".jar";
        final String url = "https://github.com/turboism/turboism-plugin-directory/releases/download/plugin-" + slug
                + "-v" + version + "/" + fileName;
        return new BuiltArtifact(
                fileName, url, jarBytes, sha256Hex(jarBytes), sha256Hex(descriptorBytes), jarBytes.length);
    }

    private static void putEntry(final JarOutputStream jar, final String name, final byte[] bytes) throws IOException {
        jar.putNextEntry(new ZipEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static PluginCatalogClient stubClient(
            final byte[] catalogBytes, final byte[] sigBytes, final Map<URI, byte[]> artifacts) {
        return new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI, PluginCatalogClient.SIGNATURE_URI, Duration.ofSeconds(5), uri -> {
                    if (PluginCatalogClient.CATALOG_URI.equals(uri)) {
                        return new StubHttpConnection(uri, 200, catalogBytes);
                    }
                    if (PluginCatalogClient.SIGNATURE_URI.equals(uri)) {
                        return new StubHttpConnection(uri, 200, sigBytes);
                    }
                    final byte[] artifact = artifacts.get(uri);
                    if (artifact != null) {
                        return new StubHttpConnection(uri, 200, artifact);
                    }
                    return new StubHttpConnection(uri, 404, new byte[0]);
                });
    }

    private static RuntimeSettingsService settings(final boolean safeMode) {
        return new RuntimeSettingsService() {
            private RuntimeSettings value = new RuntimeSettings(safeMode, "INFO", false, false, false);

            @Override
            public RuntimeSettings read() {
                return value;
            }

            @Override
            public RuntimeSettings save(final RuntimeSettings next) {
                value = next;
                return value;
            }

            @Override
            public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("done");
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 8, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
    }

    private static String sha256Hex(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record BuiltArtifact(
            String fileName, String url, byte[] bytes, String sha256, String descriptorSha256, long size) {}

    private static final class StubHttpConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private long contentLength;

        private StubHttpConnection(final URI uri, final int status, final byte[] body) throws IOException {
            super(uri.toURL());
            this.status = status;
            this.body = body;
            this.contentLength = body.length;
        }

        @Override
        public void setRequestProperty(final String key, final String value) {
            requestHeaders.put(key, value);
        }

        @Override
        public String getHeaderField(final String name) {
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }
}
