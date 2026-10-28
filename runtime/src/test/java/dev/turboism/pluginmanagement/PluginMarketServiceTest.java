package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogLocalization;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginMarketService.PluginMarketStatus;
import dev.turboism.pluginmanagement.PluginMarketService.StatusType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMarketServiceTest {

    @TempDir
    Path home;

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
