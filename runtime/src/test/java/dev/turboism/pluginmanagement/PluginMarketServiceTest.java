package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogLocalization;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadControl;
import dev.turboism.pluginmanagement.PluginMarketService.PluginMarketStatus;
import dev.turboism.pluginmanagement.PluginMarketService.StatusType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMarketServiceTest {

    @TempDir
    Path home;

    private static void installPluginJar(final Path home, final String pluginId, final String version)
            throws Exception {
        final Path pluginsDir = home.resolve("plugins");
        Files.createDirectories(pluginsDir);
        final byte[] jar = PluginManagementPackageFixture.pluginJarBytes(pluginId, version);
        Files.write(pluginsDir.resolve(pluginId + ".jar"), jar);
    }

    @Test
    void repeatedMarketDownloadStagesOneValidPackageThatAppliesAfterRestart() throws Exception {
        final byte[] jar = PluginManagementPackageFixture.pluginJarBytes("dev.market", "1.0.0");
        final Path source = java.nio.file.Files.write(home.resolve("source.jar"), jar);
        final var prepared = (dev.turboism.distribution.LocalPluginJarPreparer.Prepared)
                new dev.turboism.distribution.LocalPluginJarPreparer().prepare(source, home.resolve("fixture"));
        final var artifact = new CatalogArtifact(
                "application/java-archive",
                "test.jar",
                "https://github.com/turboism/test/releases/download/v1/test.jar",
                prepared.value().jarSha256(),
                prepared.value().descriptorSha256(),
                jar.length);
        final var release = new CatalogRelease(
                "1.0.0",
                "stable",
                "active",
                Instant.now(),
                "workflow",
                List.of(),
                "[0.67.0,1.0.0)",
                false,
                List.of(),
                List.of("windows-x64"),
                List.of(),
                List.of(),
                "https://github.com/turboism/test/releases/tag/v1",
                Optional.empty(),
                artifact,
                false);
        final var plugin = new CatalogPlugin(
                "dev.market",
                "market",
                "Market",
                "",
                "official",
                "Test",
                "MIT",
                "https://github.com/turboism/test",
                "https://github.com/turboism/test/issues",
                Map.of(),
                List.of(release));
        final PluginCatalogClient client = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                java.time.Duration.ofSeconds(5),
                uri -> new java.net.HttpURLConnection(uri.toURL()) {
                    public void connect() {}

                    public void disconnect() {}

                    public boolean usingProxy() {
                        return false;
                    }

                    public int getResponseCode() {
                        return 200;
                    }

                    public long getContentLengthLong() {
                        return jar.length;
                    }

                    public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream(jar);
                    }
                });
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        client,
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {
            for (int i = 0; i < 2; i++) {
                final var result = market.installOrUpdate(plugin, release)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertTrue(result.accepted(), result.toString());
            }
            assertEquals(1, pending.operations().size());
            final var cancelled = new PluginCatalogClient.DownloadControl();
            final var cancelledResult = market.installOrUpdate(plugin, release, cancelled, progress -> {
                        if (progress.receivedBytes() > 0) cancelled.cancel();
                    })
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("PLUGIN_DOWNLOAD_CANCELLED", cancelledResult.code());
            assertFalse(cancelledResult.accepted());
            assertEquals(1, pending.operations().size());
            assertTrue(java.nio.file.Files.exists(
                    Path.of(pending.operations().get(0).stagedJar())));
            assertTrue(pending.apply().applied());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    jar, java.nio.file.Files.readAllBytes(home.resolve("plugins/dev.market.jar")));
        }
    }

    @Test
    void resolvesAllRowsFromOneBackgroundReadAndRefreshesChangedLocalState() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicReference<List<PluginInfo>> installed = new AtomicReference<>(List.of());
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Boolean> readOnEdt = new AtomicReference<>();
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, () -> {
                    reads.incrementAndGet();
                    readOnEdt.set(SwingUtilities.isEventDispatchThread());
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test read timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    return installed.get();
                });
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        new PendingPluginOperations(home),
                        mgmt,
                        () -> Optional.of("5.3.04"),
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {
            final var rows = java.util.stream.IntStream.range(0, 29)
                    .mapToObj(i -> createPlugin("dev.plugin" + i, "1.0.0", "5.3.04", "[0.67.0,1.0.0)"))
                    .toList();
            final var catalog =
                    new PluginCatalog(PluginCatalog.FORMAT, 2, 1, Instant.now(), "0".repeat(64), "test-key", rows);
            final AtomicReference<java.util.concurrent.CompletableFuture<PluginMarketService.MarketSnapshot>> request =
                    new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(
                        () -> request.set(market.snapshot(catalog).toCompletableFuture()));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final CountDownLatch heartbeat = new CountDownLatch(1);
                SwingUtilities.invokeLater(heartbeat::countDown);
                assertTrue(heartbeat.await(2, TimeUnit.SECONDS), "slow disk must not block EDT");
                assertFalse(readOnEdt.get());
            } finally {
                release.countDown();
            }
            final var first = request.get().get(5, TimeUnit.SECONDS);
            assertEquals(1, reads.get());
            assertEquals(29, first.statuses().size());
            assertEquals(
                    StatusType.NOT_INSTALLED,
                    first.statuses().get("dev.plugin0").type());
            installed.set(List.of(new PluginInfo(
                    "dev.plugin0",
                    "Plugin",
                    "1.0.0",
                    "",
                    "ENABLED",
                    "ENABLED",
                    false,
                    Optional.empty(),
                    "workflow",
                    List.of())));
            final var second = market.snapshot(catalog).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, reads.get());
            assertEquals(
                    StatusType.INSTALLED, second.statuses().get("dev.plugin0").type());
            assertEquals(
                    StatusType.NOT_INSTALLED,
                    first.statuses().get("dev.plugin0").type());
        }
    }

    @Test
    void resolvesStatusCorrectlyAcrossAllStates() {
        final List<PluginInfo> installed = List.of(
                new PluginInfo(
                        "dev.installed",
                        "Installed Plugin",
                        "1.0.0",
                        "",
                        "ENABLED",
                        "ENABLED",
                        false,
                        Optional.empty(),
                        "workflow",
                        List.of()),
                new PluginInfo(
                        "dev.updatable",
                        "Updatable Plugin",
                        "1.0.0",
                        "",
                        "ENABLED",
                        "ENABLED",
                        false,
                        Optional.empty(),
                        "workflow",
                        List.of()));

        final RuntimePluginManagementService mgmt =
                new RuntimePluginManagementService(home, () -> Optional.empty(), () -> installed);

        final PluginMarketService market = new PluginMarketService(
                home,
                new PluginCatalogClient(),
                new PluginCatalogVerifier(),
                new PluginCatalogStateStore(home),
                new PendingPluginOperations(home),
                mgmt,
                () -> Optional.of("5.3.04"),
                dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                ignored -> {});

        // 1. Installed and up to date
        final CatalogPlugin p1 = createPlugin("dev.installed", "1.0.0", "5.3.04", "[0.67.0,1.0.0)");
        final PluginMarketStatus st1 = market.resolveStatus(p1);
        assertEquals(StatusType.INSTALLED, st1.type());
        assertEquals("1.0.0", st1.installedVersion().orElseThrow());

        // 2. Update available
        final CatalogPlugin p2 = createPlugin("dev.updatable", "1.2.0", "5.3.04", "[0.67.0,1.0.0)");
        final PluginMarketStatus st2 = market.resolveStatus(p2);
        assertEquals(StatusType.UPDATE_AVAILABLE, st2.type());
        assertEquals("1.0.0", st2.installedVersion().orElseThrow());
        assertEquals("1.2.0", st2.targetRelease().orElseThrow().version());

        // 3. Not installed
        final CatalogPlugin p3 = createPlugin("dev.fresh", "0.5.0", "5.3.04", "[0.67.0,1.0.0)");
        final PluginMarketStatus st3 = market.resolveStatus(p3);
        assertEquals(StatusType.NOT_INSTALLED, st3.type());
        assertTrue(st3.installedVersion().isEmpty());
        assertEquals("0.5.0", st3.targetRelease().orElseThrow().version());

        // 4. Incompatible due to Cubism version
        final CatalogPlugin p4 = createPlugin("dev.cubism.incompat", "2.0.0", "5.0.00", "[0.67.0,1.0.0)");
        final PluginMarketStatus st4 = market.resolveStatus(p4);
        assertEquals(StatusType.INCOMPATIBLE, st4.type());
        assertTrue(st4.incompatibleReason().orElseThrow().contains("Cubism"));

        // 5. Incompatible due to Turboism API
        final CatalogPlugin p5 = createPlugin("dev.api.incompat", "2.0.0", "5.3.04", "[0.1.0,0.2.0)");
        final PluginMarketStatus st5 = market.resolveStatus(p5);
        assertEquals(StatusType.INCOMPATIBLE, st5.type());
        assertTrue(st5.incompatibleReason().orElseThrow().contains("API"));
        assertTrue(st5.incompatibleReason()
                .orElseThrow()
                .contains(dev.turboism.core.FrameworkBuildInfo.current()
                        .apiVersion()
                        .toString()));
    }

    @Test
    void localizesPluginNameAndSummaryWithFallbackHierarchy() {
        final CatalogPlugin plugin = new CatalogPlugin(
                "dev.test.i18n",
                "test-i18n",
                "English Name",
                "English Summary",
                "official",
                "Author",
                "MIT",
                "https://github.com/turboism/test",
                "https://github.com/turboism/test/issues",
                Map.of(
                        "zh-Hans", new CatalogLocalization("简体中文名称", "简体中文摘要"),
                        "zh-Hant", new CatalogLocalization("繁體中文名稱", "繁體中文摘要"),
                        "ja", new CatalogLocalization("日本語の名前", "日本語の概要")),
                List.of(createRelease("1.0.0", "5.3.04", "[0.67.0,1.0.0)")));

        // Exact match
        final var zhHans = PluginMarketService.localizePlugin(plugin, Locale.SIMPLIFIED_CHINESE);
        assertEquals("简体中文名称", zhHans.name());
        assertEquals("简体中文摘要", zhHans.summary());

        // Traditional Chinese
        final var zhHant = PluginMarketService.localizePlugin(plugin, Locale.TRADITIONAL_CHINESE);
        assertEquals("繁體中文名稱", zhHant.name());
        assertEquals("繁體中文摘要", zhHant.summary());

        // Japanese
        final var ja = PluginMarketService.localizePlugin(plugin, Locale.JAPANESE);
        assertEquals("日本語の名前", ja.name());
        assertEquals("日本語の概要", ja.summary());

        // Fallback to English for Korean
        final var ko = PluginMarketService.localizePlugin(plugin, Locale.KOREAN);
        assertEquals("English Name", ko.name());
        assertEquals("English Summary", ko.summary());
    }

    @Test
    void pendingReadAndManagementFuturesSettleOnClose() throws Exception {
        installPluginJar(home, "dev.market.installed", "1.0.0");
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final CountDownLatch holdStarted = new CountDownLatch(1);
        final CountDownLatch releaseHold = new CountDownLatch(1);

        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            final var executorField = PluginMarketService.class.getDeclaredField("marketExecutor");
            executorField.setAccessible(true);
            final ExecutorService executor = (ExecutorService) executorField.get(market);

            try {
                executor.execute(() -> {
                    holdStarted.countDown();
                    try {
                        releaseHold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(holdStarted.await(5, TimeUnit.SECONDS), "Worker did not start in time");

                // Submit queued read and management tasks
                final var pendingRead =
                        market.loadReadme("dev.market.installed").toCompletableFuture();
                final var pendingMgmt = market.uninstall("dev.market.installed").toCompletableFuture();

                assertFalse(pendingRead.isDone());
                assertFalse(pendingMgmt.isDone());
                assertEquals(2, market.queueSize());

                market.close();

                assertTrue(pendingRead.isDone(), "Queued read future must settle on close");
                assertTrue(pendingMgmt.isDone(), "Queued management future must settle on close");
                assertTrue(pendingRead.isCompletedExceptionally());
                assertTrue(pendingMgmt.isCompletedExceptionally());

                // Queued uninstall was cancelled and NEVER staged removal
                assertEquals(0, pending.operations().size());
                assertEquals(0, market.queueSize());
            } finally {
                releaseHold.countDown();
            }

            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void operationsAfterCloseReturnDeterministicFailureWithoutSyncException() {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            market.close();

            final CatalogPlugin dummyPlugin = createPlugin("dev.closed", "1.0.0", "5.3.04", "[0.67.0,1.0.0)");
            final CatalogRelease dummyRelease = dummyPlugin.releases().get(0);
            final PluginCatalog emptyCatalog =
                    new PluginCatalog(PluginCatalog.FORMAT, 2, 1, Instant.now(), "0".repeat(64), "key", List.of());

            // Verify none of the calls throw synchronously, and all return completed exceptional futures
            final var readFuture = market.loadReadme("dev.closed").toCompletableFuture();
            final var catalogFuture = market.loadCatalog(false).toCompletableFuture();
            final var snapshotFuture = market.snapshot(emptyCatalog).toCompletableFuture();
            final var loadSnapshotFuture = market.loadSnapshot(false).toCompletableFuture();
            final var uninstallFuture = market.uninstall("dev.closed").toCompletableFuture();
            final var setEnabledFuture = market.setEnabled("dev.closed", true).toCompletableFuture();
            final var installFuture =
                    market.installOrUpdate(dummyPlugin, dummyRelease).toCompletableFuture();

            assertTrue(readFuture.isDone());
            assertTrue(readFuture.isCompletedExceptionally());
            assertTrue(catalogFuture.isDone());
            assertTrue(catalogFuture.isCompletedExceptionally());
            assertTrue(snapshotFuture.isDone());
            assertTrue(snapshotFuture.isCompletedExceptionally());
            assertTrue(loadSnapshotFuture.isDone());
            assertTrue(loadSnapshotFuture.isCompletedExceptionally());
            assertTrue(uninstallFuture.isDone());
            assertTrue(uninstallFuture.isCompletedExceptionally());
            assertTrue(setEnabledFuture.isDone());
            assertTrue(setEnabledFuture.isCompletedExceptionally());
            assertTrue(installFuture.isDone());
            assertTrue(installFuture.isCompletedExceptionally());

            final ExecutionException ex = assertThrows(ExecutionException.class, () -> uninstallFuture.get());
            assertInstanceOf(RejectedExecutionException.class, ex.getCause());
        }
    }

    @Test
    void boundedQueueRejectionReturnsDeterministicTerminalFailure() throws Exception {
        installPluginJar(home, "plugin.one", "1.0.0");
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final CountDownLatch holdStarted = new CountDownLatch(1);
        final CountDownLatch releaseHold = new CountDownLatch(1);

        // Bounded queue capacity = 1
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {},
                        1)) {

            final var executorField = PluginMarketService.class.getDeclaredField("marketExecutor");
            executorField.setAccessible(true);
            final ExecutorService executor = (ExecutorService) executorField.get(market);

            try {
                executor.execute(() -> {
                    holdStarted.countDown();
                    try {
                        releaseHold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(holdStarted.await(5, TimeUnit.SECONDS));

                // Task 1: fills the single queue slot (plugin.one is installed)
                final var task1 = market.uninstall("plugin.one").toCompletableFuture();
                assertEquals(1, market.queueSize());
                assertFalse(task1.isDone());

                // Task 2: overflows the queue capacity of 1
                final var task2 = market.uninstall("plugin.two").toCompletableFuture();

                // Task 2 must not throw synchronously and must complete exceptionally
                assertTrue(task2.isDone(), "Overflow task must settle immediately");
                assertTrue(task2.isCompletedExceptionally());
                final ExecutionException ex = assertThrows(ExecutionException.class, () -> task2.get());
                assertInstanceOf(RejectedExecutionException.class, ex.getCause());

                // Release worker; task 1 must execute normally
                releaseHold.countDown();
                final var res1 = task1.get(5, TimeUnit.SECONDS);
                assertTrue(res1.accepted());

                // Only plugin.one was staged for removal; plugin.two was never touched
                assertEquals(1, pending.operations().size());
                assertEquals("plugin.one", pending.operations().get(0).pluginId());
            } finally {
                releaseHold.countDown();
            }
        }
    }

    @Test
    void cancelQueuedRemovalPreventsExecutionAndFreesQueue() throws Exception {
        installPluginJar(home, "dev.market.to.cancel", "1.0.0");
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final CountDownLatch holdStarted = new CountDownLatch(1);
        final CountDownLatch releaseHold = new CountDownLatch(1);

        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            final var executorField = PluginMarketService.class.getDeclaredField("marketExecutor");
            executorField.setAccessible(true);
            final ExecutorService executor = (ExecutorService) executorField.get(market);

            try {
                executor.execute(() -> {
                    holdStarted.countDown();
                    try {
                        releaseHold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(holdStarted.await(5, TimeUnit.SECONDS));

                final var uninstallFuture =
                        market.uninstall("dev.market.to.cancel").toCompletableFuture();
                assertEquals(1, market.queueSize());
                assertFalse(uninstallFuture.isDone());

                // Cancel the queued removal while it is still waiting
                final boolean cancelled = uninstallFuture.cancel(true);
                assertTrue(cancelled, "Queued task must admit cancellation");
                assertTrue(uninstallFuture.isCancelled());
                assertTrue(uninstallFuture.isDone());
                assertEquals(0, market.queueSize(), "Cancelled task must be freed from queue");

                // Second cancel call must be idempotent (returns true per CompletableFuture contract, stays cancelled)
                final boolean secondCancel = uninstallFuture.cancel(true);
                assertTrue(secondCancel, "Repeated cancel call must return true");
                assertTrue(uninstallFuture.isCancelled(), "Future must remain cancelled");

                releaseHold.countDown();
                final CountDownLatch quiescent = new CountDownLatch(1);
                executor.execute(quiescent::countDown);
                assertTrue(quiescent.await(5, TimeUnit.SECONDS));

                // Verify uninstall was never executed on the installed plugin
                assertEquals(0, pending.operations().size());
            } finally {
                releaseHold.countDown();
            }
        }
    }

    @Test
    void cancelDeniedOnceOperationStartsRunning() throws Exception {
        final byte[] jar = PluginManagementPackageFixture.pluginJarBytes("dev.market.commit", "1.0.0");
        final Path source = java.nio.file.Files.write(home.resolve("source-commit.jar"), jar);
        final var prepared = (dev.turboism.distribution.LocalPluginJarPreparer.Prepared)
                new dev.turboism.distribution.LocalPluginJarPreparer().prepare(source, home.resolve("fixture-commit"));
        final var artifact = new CatalogArtifact(
                "application/java-archive",
                "test.jar",
                "https://github.com/turboism/test/releases/download/v1/test.jar",
                prepared.value().jarSha256(),
                prepared.value().descriptorSha256(),
                jar.length);
        final var release = new CatalogRelease(
                "1.0.0",
                "stable",
                "active",
                Instant.now(),
                "workflow",
                List.of(),
                "[0.67.0,1.0.0)",
                false,
                List.of(),
                List.of("windows-x64"),
                List.of(),
                List.of(),
                "https://github.com/turboism/test/releases/tag/v1",
                Optional.empty(),
                artifact,
                false);
        final var plugin = new CatalogPlugin(
                "dev.market.commit",
                "market",
                "Market Commit",
                "",
                "official",
                "Test",
                "MIT",
                "https://github.com/turboism/test",
                "https://github.com/turboism/test/issues",
                Map.of(),
                List.of(release));

        final CountDownLatch downloadEntered = new CountDownLatch(1);
        final CountDownLatch releaseDownload = new CountDownLatch(1);

        final PluginCatalogClient client = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                java.time.Duration.ofSeconds(5),
                uri -> new java.net.HttpURLConnection(uri.toURL()) {
                    public void connect() {}

                    public void disconnect() {}

                    public boolean usingProxy() {
                        return false;
                    }

                    public int getResponseCode() {
                        return 200;
                    }

                    public long getContentLengthLong() {
                        return jar.length;
                    }

                    public java.io.InputStream getInputStream() {
                        downloadEntered.countDown();
                        try {
                            if (!releaseDownload.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test timed out waiting for download release");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return new java.io.ByteArrayInputStream(jar);
                    }
                });

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        client,
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            final DownloadControl control = new DownloadControl();
            try {
                final var installFuture = market.installOrUpdate(plugin, release, control, ignored -> {})
                        .toCompletableFuture();

                // Wait until worker starts running the task and enters download
                assertTrue(downloadEntered.await(5, TimeUnit.SECONDS), "Download did not start in time");

                // Attempting to cancel while running must be denied
                final boolean cancelAdmitted = installFuture.cancel(true);
                assertFalse(cancelAdmitted, "Cancellation must be denied once execution has started");
                assertFalse(installFuture.isCancelled(), "Future must not claim cancelled when running");

                // Release download to let real staged install commit
                releaseDownload.countDown();

                final var result = installFuture.get(5, TimeUnit.SECONDS);
                assertTrue(result.accepted());
                assertEquals(1, pending.operations().size());
                assertTrue(java.nio.file.Files.exists(
                        Path.of(pending.operations().get(0).stagedJar())));
                assertTrue(pending.apply().applied());
            } finally {
                releaseDownload.countDown();
            }
        }
    }

    @Test
    void closeDuringActiveDownloadCancelsCooperativelyBeforeCommit() throws Exception {
        final byte[] jar = PluginManagementPackageFixture.pluginJarBytes("dev.market.close", "1.0.0");
        final Path source = java.nio.file.Files.write(home.resolve("source-close.jar"), jar);
        final var prepared = (dev.turboism.distribution.LocalPluginJarPreparer.Prepared)
                new dev.turboism.distribution.LocalPluginJarPreparer().prepare(source, home.resolve("fixture-close"));
        final var artifact = new CatalogArtifact(
                "application/java-archive",
                "test.jar",
                "https://github.com/turboism/test/releases/download/v1/test.jar",
                prepared.value().jarSha256(),
                prepared.value().descriptorSha256(),
                jar.length);
        final var release = new CatalogRelease(
                "1.0.0",
                "stable",
                "active",
                Instant.now(),
                "workflow",
                List.of(),
                "[0.67.0,1.0.0)",
                false,
                List.of(),
                List.of("windows-x64"),
                List.of(),
                List.of(),
                "https://github.com/turboism/test/releases/tag/v1",
                Optional.empty(),
                artifact,
                false);
        final var plugin = new CatalogPlugin(
                "dev.market.close",
                "market",
                "Market Close",
                "",
                "official",
                "Test",
                "MIT",
                "https://github.com/turboism/test",
                "https://github.com/turboism/test/issues",
                Map.of(),
                List.of(release));

        final CountDownLatch downloadEntered = new CountDownLatch(1);
        final CountDownLatch downloadBlocked = new CountDownLatch(1);
        final PluginCatalogClient client = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                java.time.Duration.ofSeconds(5),
                uri -> new java.net.HttpURLConnection(uri.toURL()) {
                    public void connect() {}

                    public void disconnect() {
                        downloadBlocked.countDown();
                    }

                    public boolean usingProxy() {
                        return false;
                    }

                    public int getResponseCode() {
                        return 200;
                    }

                    public long getContentLengthLong() {
                        return jar.length;
                    }

                    public java.io.InputStream getInputStream() {
                        return new java.io.InputStream() {
                            int pos = 0;

                            @Override
                            public int read() throws java.io.IOException {
                                if (pos == 0) {
                                    pos++;
                                    return jar[0];
                                }
                                downloadEntered.countDown();
                                try {
                                    downloadBlocked.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new java.io.InterruptedIOException("Download interrupted");
                                }
                                throw new java.io.IOException("Stream closed");
                            }
                        };
                    }
                });

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        client,
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            final DownloadControl control = new DownloadControl();
            try {
                final var installFuture = market.installOrUpdate(plugin, release, control, ignored -> {})
                        .toCompletableFuture();

                assertTrue(
                        downloadEntered.await(5, TimeUnit.SECONDS),
                        "Download did not enter read blocking state in time");

                // Close while download is stalled
                market.close();

                // Active download must be cooperatively cancelled and finish with rejected result
                final var result = installFuture.get(5, TimeUnit.SECONDS);
                assertEquals("PLUGIN_DOWNLOAD_CANCELLED", result.code());
                assertFalse(result.accepted());
                assertEquals(0, pending.operations().size(), "Cancelled install must not stage package");
            } finally {
                downloadBlocked.countDown();
            }
        }
    }

    @Test
    void futureClearsTaskReferenceOnCompletionAndCancellation() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch workerEntered = new CountDownLatch(1);

        try (var mgmt = new RuntimePluginManagementService(home, Optional::empty, List::of);
                var market = new PluginMarketService(
                        home,
                        new PluginCatalogClient(),
                        new PluginCatalogVerifier(),
                        new PluginCatalogStateStore(home),
                        pending,
                        mgmt,
                        Optional::empty,
                        dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                        ignored -> {})) {

            final var taskField = Class.forName("dev.turboism.pluginmanagement.PluginMarketService$MarketFuture")
                    .getDeclaredField("task");
            taskField.setAccessible(true);

            final var executorField = PluginMarketService.class.getDeclaredField("marketExecutor");
            executorField.setAccessible(true);
            final ExecutorService executor = (ExecutorService) executorField.get(market);

            try {
                // 1. Normal completion clears task reference after executor barrier completes
                final var normalFuture = market.loadReadme("dev.test.readme").toCompletableFuture();
                final var res = normalFuture.get(5, TimeUnit.SECONDS);
                assertTrue(res.isEmpty());
                assertTrue(normalFuture.isDone());

                // Barrier ensures whenComplete callback on worker has finished
                final CountDownLatch barrier = new CountDownLatch(1);
                executor.execute(barrier::countDown);
                assertTrue(barrier.await(5, TimeUnit.SECONDS));
                assertNull(taskField.get(normalFuture), "Completed future must clear task reference");

                // 2. Cancellation clears task reference immediately
                executor.execute(() -> {
                    workerEntered.countDown();
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS));

                final var cancelFuture = market.uninstall("dev.test.cancel").toCompletableFuture();
                assertNotNull(taskField.get(cancelFuture), "Queued future should hold task before completion");

                assertTrue(cancelFuture.cancel(true));
                assertTrue(cancelFuture.isDone());
                assertTrue(cancelFuture.isCancelled());
                assertNull(taskField.get(cancelFuture), "Cancelled future must clear task reference");
            } finally {
                hold.countDown();
            }
        }
    }

    private static CatalogPlugin createPlugin(
            final String id, final String version, final String cubismVersion, final String apiRange) {
        return new CatalogPlugin(
                id,
                id.substring(id.lastIndexOf('.') + 1),
                id,
                "Summary for " + id,
                "official",
                "Author",
                "MIT",
                "https://github.com/turboism/test",
                "https://github.com/turboism/test/issues",
                Map.of(),
                List.of(createRelease(version, cubismVersion, apiRange)));
    }

    private static CatalogRelease createRelease(
            final String version, final String cubismVersion, final String apiRange) {
        return new CatalogRelease(
                version,
                "stable",
                "active",
                Instant.parse("2026-10-13T10:00:00Z"),
                "workflow",
                List.of("test"),
                apiRange,
                true,
                List.of(cubismVersion),
                List.of("windows-x64"),
                List.of(),
                List.of(new CatalogPermission("turboism.file.read", "application", "Reads files")),
                "https://github.com/turboism/test/releases/tag/v" + version,
                Optional.empty(),
                new CatalogArtifact(
                        "application/java-archive",
                        "test-" + version + ".jar",
                        "https://github.com/turboism/test/releases/download/v" + version + "/test-" + version + ".jar",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                        1024L),
                true // hotReload
                );
    }
}
