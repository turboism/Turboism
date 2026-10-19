package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMarketServiceTest {

    @TempDir
    Path home;

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
