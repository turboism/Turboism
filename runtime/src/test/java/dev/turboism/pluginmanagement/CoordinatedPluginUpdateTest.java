package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.CommitResult;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.CompatibilityStatus;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.DownloadResult;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.PluginUpdateRow;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.TrustedKey;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatedPluginUpdateTest {

    private static final String TEST_KEY_ID = "test-authority-2026";

    @TempDir
    Path tempHome;

    private KeyPair keyPair;
    private PluginCatalogVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        final String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        verifier = new PluginCatalogVerifier(
                Map.of(TEST_KEY_ID, new TrustedKey(TEST_KEY_ID, pem, "test", keyPair.getPublic())), false);
    }

    @AfterEach
    void tearDown() {
        RuntimePluginAutoUpdater.testBeforeStageHook = null;
    }

    @Test
    void dualCompatibilityAndFutureOnlyPlanning() throws Exception {
        final String pluginA = "dev.turboism.plugin.alpha";
        final String pluginB = "dev.turboism.plugin.beta";

        final BuiltArtifact artA = buildArtifact(pluginA, "alpha", "1.1.0", "[0.43.0,0.45.0)", false, List.of());
        final BuiltArtifact artB = buildArtifact(pluginB, "beta", "2.0.0", "[0.44.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "alpha", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artA))),
                pluginJson(pluginB, "beta", "official", List.of(
                        releaseJson("2.0.0", "stable", "active", "[0.44.0,0.45.0)", false, List.of(), List.of(), artB)))));

        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog catalog = verifier.verify(catalogBytes, sigBytes);

        final PluginVersion currentApi = PluginVersion.parse("0.43.0");
        final PluginVersion targetApi = PluginVersion.parse("0.44.0");

        final List<CorePluginManagement.PluginInfo> installed = List.of(
                new CorePluginManagement.PluginInfo(pluginA, "Alpha", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of()),
                new CorePluginManagement.PluginInfo(pluginB, "Beta", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of()));

        final CoordinatedPluginUpdateService service = new CoordinatedPluginUpdateService(
                tempHome,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), artA.bytes())),
                new PendingPluginOperations(tempHome),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                currentApi);

        final List<PluginUpdateRow> rows = service.planCoordinatedUpdates(catalog, installed, Optional.of(targetApi));
        assertEquals(2, rows.size());

        final PluginUpdateRow rowA = rows.stream().filter(r -> r.pluginId().equals(pluginA)).findFirst().orElseThrow();
        assertEquals(CompatibilityStatus.DUAL_COMPATIBLE, rowA.status());
        assertEquals("1.1.0", rowA.targetVersion());
        assertTrue(rowA.selected(), "Enabled dual-compatible plugin should be selected by default");

        final PluginUpdateRow rowB = rows.stream().filter(r -> r.pluginId().equals(pluginB)).findFirst().orElseThrow();
        assertEquals(CompatibilityStatus.FUTURE_ONLY, rowB.status());
        assertFalse(rowB.selected(), "Future-only plugin must not be selected by default");
        assertTrue(rowB.explanation().contains("target API only"), "Explanation should note rollback unsafety");
    }

    @Test
    void dependencyAutoSelectionAndDeselection() throws Exception {
        final String pluginA = "dev.turboism.plugin.main";
        final String pluginB = "dev.turboism.plugin.dep";

        final String depDecl = "{\"id\":\"" + pluginB + "\",\"version\":\"[1.1.0,2.0.0)\",\"type\":\"required\",\"ordering\":\"none\"}";
        final BuiltArtifact artB = buildArtifact(pluginB, "dep", "1.1.0", "[0.43.0,0.45.0)", false, List.of());
        final BuiltArtifact artA = buildArtifact(pluginA, "main", "1.1.0", "[0.43.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "main", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(depDecl), artA))),
                pluginJson(pluginB, "dep", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artB)))));

        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog catalog = verifier.verify(catalogBytes, sigBytes);

        final PluginVersion currentApi = PluginVersion.parse("0.43.0");
        final PluginVersion targetApi = PluginVersion.parse("0.44.0");

        // Plugin B is disabled initially; plugin A is enabled
        final List<CorePluginManagement.PluginInfo> installed = List.of(
                new CorePluginManagement.PluginInfo(pluginA, "Main", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of()),
                new CorePluginManagement.PluginInfo(pluginB, "Dep", "1.0.0", "", "DISABLED", "DISABLED", false, Optional.empty(), "integration", List.of()));

        final CoordinatedPluginUpdateService service = new CoordinatedPluginUpdateService(
                tempHome,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), artA.bytes(), URI.create(artB.url()), artB.bytes())),
                new PendingPluginOperations(tempHome),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                currentApi);

        List<PluginUpdateRow> rows = service.planCoordinatedUpdates(catalog, installed, Optional.of(targetApi));

        // Dependency closure on default selection: B was disabled, but A requires B, so B must be auto-selected!
        final PluginUpdateRow depRowInitial = rows.stream().filter(r -> r.pluginId().equals(pluginB)).findFirst().orElseThrow();
        assertTrue(depRowInitial.selected(), "Required dependency B must be auto-selected when A is selected");

        // Uncheck dependency: main should automatically be unchecked
        rows = service.toggleSelection(rows, pluginB, false);
        final PluginUpdateRow mainRowAfterDepUncheck = rows.stream().filter(r -> r.pluginId().equals(pluginA)).findFirst().orElseThrow();
        assertFalse(mainRowAfterDepUncheck.selected(), "Unchecking dependency B must deselect depending plugin A");

        // Re-check main: dependency B should automatically be re-checked
        rows = service.toggleSelection(rows, pluginA, true);
        final PluginUpdateRow depRowAfterMainCheck = rows.stream().filter(r -> r.pluginId().equals(pluginB)).findFirst().orElseThrow();
        assertTrue(depRowAfterMainCheck.selected(), "Checking plugin A must auto-select required dependency B");
    }

    @Test
    void downloadDoesNotStageUntilExplicitCommit() throws Exception {
        final String pluginA = "dev.turboism.plugin.isolated";
        final BuiltArtifact artA = buildArtifact(pluginA, "isolated", "1.1.0", "[0.43.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "isolated", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artA)))));

        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog catalog = verifier.verify(catalogBytes, sigBytes);

        final PluginVersion currentApi = PluginVersion.parse("0.43.0");
        final PluginVersion targetApi = PluginVersion.parse("0.44.0");

        final List<CorePluginManagement.PluginInfo> installed = List.of(
                new CorePluginManagement.PluginInfo(pluginA, "Isolated", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of()));

        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);

        final CoordinatedPluginUpdateService service = new CoordinatedPluginUpdateService(
                tempHome,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), artA.bytes())),
                pending,
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                currentApi);

        final List<PluginUpdateRow> rows = service.planCoordinatedUpdates(catalog, installed, Optional.of(targetApi));

        // 1. Download and prepare
        final DownloadResult downloadRes = service.downloadAndPrepare(rows, Optional.of(targetApi), new AtomicBoolean(false));
        assertTrue(downloadRes.succeeded());
        assertEquals(1, downloadRes.prepared().size());

        // CRITICAL CHECK: pending.json must remain completely empty after download!
        assertTrue(pending.operations().isEmpty(), "Download must NOT write to pending operations journal");

        // 2. Explicit commit (Install on exit)
        final CommitResult commitRes = service.commitStagedBatch(downloadRes.prepared(), targetApi, installed);
        assertTrue(commitRes.succeeded());

        // Now journal has exactly 1 operation
        assertEquals(1, pending.operations().size());
        assertEquals(pluginA, pending.operations().get(0).pluginId());
        assertEquals("1.1.0", pending.operations().get(0).version());
    }

    @Test
    void downloadFailureLeavesNoStagedEntries() throws Exception {
        final String pluginA = "dev.turboism.plugin.fail";
        final BuiltArtifact artA = buildArtifact(pluginA, "fail", "1.1.0", "[0.43.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "fail", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artA)))));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);
        final PluginCatalog catalog = verifier.verify(catalogBytes, sigBytes);

        // Corrupted artifact bytes (hash mismatch)
        final byte[] corruptBytes = artA.bytes().clone();
        corruptBytes[corruptBytes.length - 1] ^= 0xFF;

        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);

        final CoordinatedPluginUpdateService service = new CoordinatedPluginUpdateService(
                tempHome,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), corruptBytes)),
                pending,
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.43.0"));

        final List<PluginUpdateRow> rows = service.planCoordinatedUpdates(
                catalog,
                List.of(new CorePluginManagement.PluginInfo(pluginA, "Fail", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of())),
                Optional.of(PluginVersion.parse("0.44.0")));

        final DownloadResult res = service.downloadAndPrepare(rows, Optional.of(PluginVersion.parse("0.44.0")), new AtomicBoolean(false));
        assertFalse(res.succeeded(), "Corrupted download must fail");
        assertTrue(pending.operations().isEmpty(), "Failure must leave no pending operations");
    }

    @Test
    void autoUpdaterInFlightCommitBlockedByGateWithCountDownLatch() throws Exception {
        final String pluginA = "dev.turboism.plugin.inflight";
        final BuiltArtifact artA = buildArtifact(pluginA, "inflight", "1.1.0", "[0.43.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "inflight", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artA)))));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);

        // Install local plugin jar v1.0.0
        final Path pluginJar = tempHome.resolve("plugins").resolve(pluginA + ".jar");
        Files.createDirectories(pluginJar.getParent());
        final BuiltArtifact localArt = buildArtifact(pluginA, "inflight", "1.0.0", "[0.43.0,0.45.0)", false, List.of());
        Files.write(pluginJar, localArt.bytes());

        final CountDownLatch reachedStageLatch = new CountDownLatch(1);
        final CountDownLatch allowStageLatch = new CountDownLatch(1);

        RuntimePluginAutoUpdater.testBeforeStageHook = () -> {
            reachedStageLatch.countDown();
            try {
                allowStageLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        final RuntimeSettingsService settings = new StubSettingsService();
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = new dev.turboism.core.runtime.RuntimeScheduler(
                new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
                new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(1, 8, ignored -> {}, java.time.Clock.systemUTC()),
                dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
                ignored -> {});
        final RuntimePluginAutoUpdater autoUpdater = new RuntimePluginAutoUpdater(
                tempHome,
                scheduler,
                settings,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), artA.bytes())),
                verifier,
                java.time.Clock.systemUTC(),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.43.0"),
                Duration.ZERO,
                Duration.ofHours(24),
                ignored -> {});

        try {
            // Start auto-updater on background thread
            final java.util.concurrent.CompletableFuture<PluginAutoUpdateControl.Snapshot> future =
                    autoUpdater.checkForPluginUpdates().toCompletableFuture();

            // Wait for auto-updater to reach the hook immediately before staging
            assertTrue(reachedStageLatch.await(5, TimeUnit.SECONDS), "Auto updater must reach staging hook");

            // Acquire manual update lease while auto-updater is paused in-flight
            final Optional<PluginUpdateGate.Lease> lease = PluginUpdateGate.acquireLease(tempHome);
            assertTrue(lease.isPresent(), "Manual lease must be acquired");

            // Now release the auto-updater to attempt staging
            allowStageLatch.countDown();

            // Auto-updater should finish and fail the stage attempt because gate is held
            final PluginAutoUpdateControl.Snapshot snap = future.join();
            assertEquals(0, snap.stagedUpdates().size(), "Auto updater must not stage updates while gate is held");

            final PendingPluginOperations pending = new PendingPluginOperations(tempHome);
            assertTrue(pending.operations().isEmpty(), "Auto-updater must be rejected by gate and leave pending journal empty");

            lease.get().close();
        } finally {
            RuntimePluginAutoUpdater.testBeforeStageHook = null;
            autoUpdater.close();
            allowStageLatch.countDown();
        }
    }

    @Test
    void stageBatchRejectsDuplicateIdsAndBadLastItemWithoutModifyingJournal() throws Exception {
        final Path stagingPackages = tempHome.resolve("state/runtime/plugin-management/packages");
        Files.createDirectories(stagingPackages);

        final String p1 = "dev.turboism.plugin.one";
        final BuiltArtifact art1 = buildArtifact(p1, "one", "1.1.0", "[0.43.0,0.45.0)", false, List.of());
        final Path temp1 = Files.createTempFile(stagingPackages, "stage-", ".jar");
        Files.write(temp1, art1.bytes());
        final dev.turboism.distribution.LocalPluginJarPreparer preparer = new dev.turboism.distribution.LocalPluginJarPreparer();
        final PreparedPluginJar prep1 = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(temp1, stagingPackages)).value();
        Files.deleteIfExists(temp1);

        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);

        // 1. Batch with duplicate IDs
        final PendingPluginOperations.StagedBatchResult dupResult = pending.stageBatch(List.of(prep1, prep1));
        assertFalse(dupResult.accepted());
        assertEquals("PLUGIN_BATCH_DUPLICATE_ID", dupResult.code());
        assertTrue(pending.operations().isEmpty(), "Journal must not be modified when batch has duplicates");

        // 2. Batch where last item is tampered
        final String p2 = "dev.turboism.plugin.two";
        final BuiltArtifact art2 = buildArtifact(p2, "two", "1.1.0", "[0.43.0,0.45.0)", false, List.of());
        final Path temp2 = Files.createTempFile(stagingPackages, "stage-", ".jar");
        Files.write(temp2, art2.bytes());
        final PreparedPluginJar prep2 = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(temp2, stagingPackages)).value();
        Files.deleteIfExists(temp2);

        // Tamper prep2 file
        Files.write(prep2.stagedJar(), new byte[] {1, 2, 3});

        final PendingPluginOperations.StagedBatchResult tamperedResult = pending.stageBatch(List.of(prep1, prep2));
        assertFalse(tamperedResult.accepted());
        assertEquals("PLUGIN_TAMPERED", tamperedResult.code());
        assertTrue(pending.operations().isEmpty(), "Journal must not be modified when any item in batch is invalid");
    }

    @Test
    void autoUpdaterBarrierBetweenStageAndHotApplyBlocksManualLease() throws Exception {
        final String pluginA = "dev.turboism.plugin.barrier";
        final BuiltArtifact artA = buildArtifact(pluginA, "barrier", "1.1.0", "[0.43.0,0.45.0)", false, List.of());

        final byte[] catalogBytes = catalogWithPlugins(1L, List.of(
                pluginJson(pluginA, "barrier", "official", List.of(
                        releaseJson("1.1.0", "stable", "active", "[0.43.0,0.45.0)", false, List.of(), List.of(), artA)))));
        final byte[] sigBytes = signEnvelope(catalogBytes, keyPair.getPrivate(), TEST_KEY_ID);

        final Path pluginJar = tempHome.resolve("plugins").resolve(pluginA + ".jar");
        Files.createDirectories(pluginJar.getParent());
        final BuiltArtifact localArt = buildArtifact(pluginA, "barrier", "1.0.0", "[0.43.0,0.45.0)", false, List.of());
        Files.write(pluginJar, localArt.bytes());

        final CountDownLatch insideCriticalSectionLatch = new CountDownLatch(1);
        final CountDownLatch allowCriticalSectionExitLatch = new CountDownLatch(1);

        RuntimePluginAutoUpdater.testBetweenStageAndHotApplyHook = () -> {
            insideCriticalSectionLatch.countDown();
            try {
                allowCriticalSectionExitLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        final RuntimeSettingsService settings = new StubSettingsService();
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = new dev.turboism.core.runtime.RuntimeScheduler(
                new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
                new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(1, 8, ignored -> {}, java.time.Clock.systemUTC()),
                dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
                ignored -> {});
        final RuntimePluginAutoUpdater autoUpdater = new RuntimePluginAutoUpdater(
                tempHome,
                scheduler,
                settings,
                stubClient(catalogBytes, sigBytes, Map.of(URI.create(artA.url()), artA.bytes())),
                verifier,
                java.time.Clock.systemUTC(),
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.43.0"),
                Duration.ZERO,
                Duration.ofHours(24),
                ignored -> {});

        try {
            final CompletableFuture<PluginAutoUpdateControl.Snapshot> future =
                    autoUpdater.checkForPluginUpdates().toCompletableFuture();

            assertTrue(insideCriticalSectionLatch.await(5, TimeUnit.SECONDS), "Must reach hook inside critical section");

            final CountDownLatch competitorStarted = new CountDownLatch(1);
            final var competitor = CompletableFuture.supplyAsync(() -> {
                competitorStarted.countDown();
                return PluginUpdateGate.acquireLease(tempHome);
            });
            assertTrue(competitorStarted.await(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> competitor.get(100, TimeUnit.MILLISECONDS), "Lease cannot enter the auto mutation section");
            allowCriticalSectionExitLatch.countDown();
            future.get(5, TimeUnit.SECONDS);
            try (var lease = competitor.get(5, TimeUnit.SECONDS).orElseThrow()) {
                assertTrue(PluginUpdateGate.isHeld(tempHome));
            }
        } finally {
            RuntimePluginAutoUpdater.testBetweenStageAndHotApplyHook = null;
            autoUpdater.close();
            allowCriticalSectionExitLatch.countDown();
        }
    }

    @Test
    void preCommitRejectsIncompatibleExistingPendingAndEnabledPlugins() throws Exception {
        final Path pluginsDir = tempHome.resolve("plugins");
        Files.createDirectories(pluginsDir);

        // Case 1: Non-<id>.jar naming (e.g. custom-named-tool.jar) for an enabled plugin incompatible with 0.44.0
        final String enabledOldId = "dev.turboism.plugin.old";
        final BuiltArtifact oldArt = buildArtifact(enabledOldId, "old", "1.0.0", "[0.43.0,0.43.99)", false, List.of());
        Files.write(pluginsDir.resolve("custom-named-tool.jar"), oldArt.bytes());

        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);
        final CoordinatedPluginUpdateService service = new CoordinatedPluginUpdateService(
                tempHome,
                new PluginCatalogClient(),
                pending,
                FrameworkBuildInfo.Channel.STABLE,
                Optional::empty,
                PluginVersion.parse("0.43.0"));

        final List<CorePluginManagement.PluginInfo> installed = List.of(
                new CorePluginManagement.PluginInfo(enabledOldId, "Old", "1.0.0", "", "LIVE", "LIVE", false, Optional.empty(), "integration", List.of()));

        // Framework-only commit (preparedBatch is empty): must still detect incompatible enabled plugin!
        final CommitResult res1 = service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), installed);
        assertFalse(res1.succeeded());
        assertTrue(res1.message().contains("does not support target framework API 0.44.0"));

        // Case 2: Existing pending install incompatible with target API 0.44.0
        final Path stagingPackages = tempHome.resolve("state/runtime/plugin-management/packages");
        Files.createDirectories(stagingPackages);
        final String pendingId = "dev.turboism.plugin.pendingbad";
        final BuiltArtifact badArt = buildArtifact(pendingId, "pendingbad", "1.0.0", "[0.43.0,0.43.99)", false, List.of());
        final Path tempStaged = Files.createTempFile(stagingPackages, "stage-", ".jar");
        Files.write(tempStaged, badArt.bytes());
        final dev.turboism.distribution.LocalPluginJarPreparer preparer = new dev.turboism.distribution.LocalPluginJarPreparer();
        final PreparedPluginJar prepBad = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(tempStaged, stagingPackages)).value();
        Files.deleteIfExists(tempStaged);
        pending.stage(prepBad);

        final CommitResult res2 = service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), List.of());
        assertFalse(res2.succeeded());
        assertTrue(res2.message().contains("Existing pending plugin 'dev.turboism.plugin.pendingbad'"));

        // Case 3: Implicit downgrade rejection
        final BuiltArtifact v09Art = buildArtifact(pendingId, "pendingbad", "0.9.0", "[0.43.0,0.45.0)", false, List.of());
        final Path tempStagedDowngrade = Files.createTempFile(stagingPackages, "stage-", ".jar");
        Files.write(tempStagedDowngrade, v09Art.bytes());
        final PreparedPluginJar prepDowngrade = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(tempStagedDowngrade, stagingPackages)).value();
        Files.deleteIfExists(tempStagedDowngrade);

        final CommitResult res3 = service.commitStagedBatch(List.of(prepDowngrade), PluginVersion.parse("0.44.0"), List.of());
        assertFalse(res3.succeeded());
        assertTrue(res3.message().contains("Cannot downgrade plugin"));
    }

    @Test
    void simulateRestartAppliesDualCompatiblePluginsAndPreservesThirdParty() throws Exception {
        final Path pluginsDir = tempHome.resolve("plugins");
        Files.createDirectories(pluginsDir);

        // 1. Existing official plugin to be updated
        final String officialId = "dev.turboism.plugin.official";
        final BuiltArtifact v1Art = buildArtifact(officialId, "official", "1.0.0", "[0.43.0,0.45.0)", false, List.of());
        Files.write(pluginsDir.resolve(officialId + ".jar"), v1Art.bytes());

        // 2. Existing third-party plugin that must NOT be touched
        final String thirdPartyId = "com.thirdparty.tool";
        final BuiltArtifact thirdPartyArt = buildArtifact(thirdPartyId, "thirdparty", "0.9.0", "[0.43.0,0.45.0)", false, List.of());
        Files.write(pluginsDir.resolve(thirdPartyId + ".jar"), thirdPartyArt.bytes());

        // 3. Stage batch update for official plugin to 1.1.0
        final BuiltArtifact v11Art = buildArtifact(officialId, "official", "1.1.0", "[0.43.0,0.45.0)", false, List.of());
        final Path stagingPackages = tempHome.resolve("state/runtime/plugin-management/packages");
        Files.createDirectories(stagingPackages);
        final Path tempStaging = Files.createTempFile(stagingPackages, "stage-", ".jar");
        Files.write(tempStaging, v11Art.bytes());

        final dev.turboism.distribution.LocalPluginJarPreparer preparer = new dev.turboism.distribution.LocalPluginJarPreparer();
        final dev.turboism.distribution.PreparedPluginJar prep = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared)
                preparer.prepare(tempStaging, stagingPackages)).value();
        Files.deleteIfExists(tempStaging);

        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);
        final PendingPluginOperations.StagedBatchResult batchResult = pending.stageBatch(List.of(prep));
        assertTrue(batchResult.accepted());

        // 4. Simulate next launch: apply pending operations
        final PendingPluginOperations.ApplyResult applyResult = RuntimePluginManagementService.applyPending(tempHome);
        assertTrue(applyResult.applied(), "Pending operations must apply cleanly");

        // 5. Verify official plugin was updated to 1.1.0
        final Path updatedJar = pluginsDir.resolve(officialId + ".jar");
        assertTrue(Files.isRegularFile(updatedJar));
        final Optional<PluginArchiveMetadata> meta = PluginArchiveMetadata.read(updatedJar);
        assertTrue(meta.isPresent());
        assertEquals("1.1.0", meta.get().version());

        // 6. Verify third-party plugin is still present and intact!
        final Path thirdPartyJar = pluginsDir.resolve(thirdPartyId + ".jar");
        assertTrue(Files.isRegularFile(thirdPartyJar), "Third party plugin must be preserved intact");
        final Optional<PluginArchiveMetadata> thirdMeta = PluginArchiveMetadata.read(thirdPartyJar);
        assertTrue(thirdMeta.isPresent());
        assertEquals("0.9.0", thirdMeta.get().version());
    }

    @Test
    void finalDependencyGraphHonorsPendingRemovalAndDoesNotEnableDisabledPlugins() throws Exception {
        final String a = "dev.turboism.plugin.grapha", b = "dev.turboism.plugin.graphb";
        final Path dir = tempHome.resolve("plugins");
        Files.createDirectories(dir);
        final BuiltArtifact plainA = buildArtifact(a, "graph-a", "1.0.0", "[0.43.0,0.45.0)", false, List.of());
        final byte[] aBytes = withDependency(plainA.bytes(), b);
        Files.write(dir.resolve("graph-a-versioned.jar"), aBytes);
        Files.write(dir.resolve("graph-b-versioned.jar"), buildArtifact(b, "graph-b", "1.0.0", "[0.43.0,0.45.0)", false, List.of()).bytes());
        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);
        final var service = new CoordinatedPluginUpdateService(tempHome, new PluginCatalogClient(), pending,
                FrameworkBuildInfo.Channel.STABLE, Optional::empty, PluginVersion.parse("0.43.0"));
        final var activeA = info(a, true);
        final var activeB = info(b, true);
        assertTrue(service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), List.of(activeA, activeB)).succeeded());
        assertFalse(service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), List.of(activeA, info(b, false))).succeeded());
        assertTrue(pending.stageUninstall(b).accepted());
        final byte[] originalJournal = Files.readAllBytes(tempHome.resolve("state/runtime/plugin-management/pending.json"));
        assertFalse(service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), List.of(activeA, activeB)).succeeded());
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalJournal,
                Files.readAllBytes(tempHome.resolve("state/runtime/plugin-management/pending.json")));
        // A disabled, non-loading plugin's unmet dependency must not block an unrelated framework update.
        assertTrue(service.commitStagedBatch(List.of(), PluginVersion.parse("0.44.0"), List.of(info(a, false), activeB)).succeeded());
    }

    @Test
    void atomicBatchRejectsChangedSnapshotDuplicateIdsAndBadLastItem() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(tempHome);
        final Path packages = tempHome.resolve("state/runtime/plugin-management/packages");
        Files.createDirectories(packages);
        final Path aJar = tempHome.resolve("a.jar"), bJar = tempHome.resolve("b.jar");
        Files.write(aJar, buildArtifact("dev.turboism.plugin.batcha", "batch-a", "1.0.0", "[0.43.0,0.45.0)", false, List.of()).bytes());
        Files.write(bJar, buildArtifact("dev.turboism.plugin.batchb", "batch-b", "1.0.0", "[0.43.0,0.45.0)", false, List.of()).bytes());
        final var preparer = new dev.turboism.distribution.LocalPluginJarPreparer();
        final var a = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(aJar, packages)).value();
        final var b = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparer.prepare(bJar, packages)).value();
        final var before = pending.operations();
        assertTrue(pending.stage(a).accepted());
        final Path journal = tempHome.resolve("state/runtime/plugin-management/pending.json");
        final byte[] original = Files.readAllBytes(journal);
        assertFalse(pending.stageBatch(List.of(b), before).accepted());
        assertFalse(pending.stageBatch(List.of(a, a)).accepted());
        Files.writeString(b.stagedJar(), "tampered");
        assertFalse(pending.stageBatch(List.of(a, b)).accepted());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(journal));
    }

    private static CorePluginManagement.PluginInfo info(final String id, final boolean enabled) {
        return new CorePluginManagement.PluginInfo(id, id, "1.0.0", "", "LIVE", enabled ? "ENABLED" : "DISABLED",
                false, Optional.empty(), "integration", List.of());
    }

    private static byte[] withDependency(final byte[] jarBytes, final String id) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var in = new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(jarBytes));
                var out = new JarOutputStream(bytes)) {
            for (java.util.jar.JarEntry entry; (entry = in.getNextJarEntry()) != null;) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("META-INF/turboism/plugin.json")) {
                    String text = new String(data, StandardCharsets.UTF_8).replace("\"dependencies\":[]",
                            "\"dependencies\":[{\"id\":\"" + id + "\",\"type\":\"required\",\"version\":\"[1.0.0,2.0.0)\",\"ordering\":\"before\"}]");
                    data = text.getBytes(StandardCharsets.UTF_8);
                }
                putEntry(out, entry.getName(), data);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] catalogWithPlugins(final long catalogVersion, final List<String> pluginsJson) {
        return ("{\"format\":\"turboism.plugin.catalog\",\"schemaVersion\":2,\"catalogVersion\":"
                        + catalogVersion
                        + ",\"publishedAt\":\"2026-10-01T00:00:00Z\",\"plugins\":["
                        + String.join(",", pluginsJson)
                        + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String pluginJson(
            final String pluginId,
            final String slug,
            final String trust,
            final List<String> releasesJson) {
        return "{"
                + "\"id\":\"" + pluginId + "\","
                + "\"slug\":\"" + slug + "\","
                + "\"name\":\"Example " + slug + "\","
                + "\"summary\":\"Summary " + slug + "\","
                + "\"trust\":\"" + trust + "\","
                + "\"author\":\"Turboism Contributors\","
                + "\"license\":\"Project License\","
                + "\"repository\":\"https://github.com/turboism/Turboism\","
                + "\"support\":\"https://github.com/turboism/Turboism/issues\","
                + "\"localizations\":{},"
                + "\"releases\":[" + String.join(",", releasesJson) + "]"
                + "}";
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
                + "\"releaseUrl\":\"https://github.com/turboism/turboism-plugin-directory/releases/tag/plugin-" + version + "\","
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
        return ("{\"format\":\"turboism.plugin.catalog.signature\",\"schemaVersion\":2,\"algorithm\":\"Ed25519\","
                        + "\"keyId\":\"" + keyId + "\","
                        + "\"catalogSha256\":\"" + sha256Hex(catalogBytes) + "\","
                        + "\"signature\":\"" + signatureBase64 + "\"}")
                .getBytes(StandardCharsets.UTF_8);
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
                + "\",\"name\":\"" + slug + "\",\"version\":\"" + version + "\",\"description\":\"Test\","
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
            putEntry(jar, "META-INF/turboism/i18n/messages.properties", "plugin.name=Test\n".getBytes(StandardCharsets.UTF_8));
            putEntry(jar, entrypoint.replace('.', '/') + ".class", new byte[] {0});
            if (requiresCubism) {
                final String versionsJson = supportedCubism.stream()
                        .map(v -> "\"" + v + "\"")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                final String sidecar = "{\"format\":\"turboism.plugin-cubism-versions/v1\",\"plugin\":\"" + id
                        + "\",\"supportedVersions\":[" + versionsJson + "],\"fullFeatureVersions\":[" + versionsJson + "]}";
                putEntry(jar, "META-INF/turboism/cubism-versions.json", sidecar.getBytes(StandardCharsets.UTF_8));
            }
        }
        final byte[] jarBytes = out.toByteArray();
        final String fileName = slug + "-" + version + ".jar";
        final String url = "https://github.com/turboism/turboism-plugin-directory/releases/download/plugin-" + slug
                + "-v" + version + "/" + fileName;
        return new BuiltArtifact(fileName, url, jarBytes, sha256Hex(jarBytes), sha256Hex(descriptorBytes), jarBytes.length);
    }

    private static void putEntry(final JarOutputStream jar, final String name, final byte[] bytes) throws IOException {
        jar.putNextEntry(new ZipEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static String sha256Hex(final byte[] data) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(data));
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

    private record BuiltArtifact(
            String fileName, String url, byte[] bytes, String sha256, String descriptorSha256, long size) {}

    private static final class StubHttpConnection extends HttpURLConnection {
        private final byte[] responseBytes;

        StubHttpConnection(final URI uri, final int responseCode, final byte[] responseBytes) {
            super(toUrl(uri));
            this.responseCode = responseCode;
            this.responseBytes = responseBytes;
        }

        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(responseBytes); }
        @Override public int getResponseCode() { return responseCode; }

        private static URL toUrl(final URI uri) {
            try {
                return new URL("http", "example.test", 80, uri.getPath(), new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL u) { throw new UnsupportedOperationException(); }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class StubSettingsService implements dev.turboism.sdk.runtime.RuntimeSettingsService {
        private final dev.turboism.sdk.runtime.RuntimeSettings settings =
                new dev.turboism.sdk.runtime.RuntimeSettings(false, "INFO", 100, false, false, false, false, "en", false);

        @Override public dev.turboism.sdk.runtime.RuntimeSettings read() { return settings; }
        @Override public dev.turboism.sdk.runtime.RuntimeSettings save(dev.turboism.sdk.runtime.RuntimeSettings value) { return value; }
        @Override public DockCleanupResult cleanEmptyDocks() { return new DockCleanupResult("ok"); }
    }
}
