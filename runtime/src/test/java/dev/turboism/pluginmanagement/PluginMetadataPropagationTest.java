package dev.turboism.pluginmanagement;

import dev.turboism.plugin.core.CorePluginManagement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema v3 classification must propagate into installed-plugin management rows. */
class PluginMetadataPropagationTest {
    @TempDir Path home;

    @Test
    void v3InstalledPluginExposesRegisteredCategoryAndOrderedTags() throws Exception {
        installJar("v3.plugin", "1.0.0", "modeling", List.of("parameter", "batch-edit"));

        final CorePluginManagement.PluginInfo row = row("v3.plugin");

        assertEquals("modeling", row.category());
        assertEquals(List.of("parameter", "batch-edit"), row.tags());
    }

    @Test
    void v2InstalledPluginFallsBackToOtherWithEmptyTags() throws Exception {
        installJar("v2.plugin", "1.0.0", null, List.of());

        final CorePluginManagement.PluginInfo row = row("v2.plugin");

        assertEquals("other", row.category());
        assertEquals(List.of(), row.tags());
    }

    @Test
    void wellFormedUnknownCategoryFallsBackToOtherWithoutRewritingDescriptor() throws Exception {
        installJar("unknown-category.plugin", "1.0.0", "custom-tooling", List.of("local"));

        final CorePluginManagement.PluginInfo row = row("unknown-category.plugin");

        assertEquals("other", row.category());
        assertEquals(List.of("local"), row.tags());
    }

    @Test
    void pendingInstallRowUsesOtherWithEmptyTags() throws Exception {
        writePendingInstall("pending.plugin", "1.0.0");

        final CorePluginManagement.PluginInfo row = service().plugins().stream()
            .filter(plugin -> plugin.id().equals("pending.plugin"))
            .findFirst().orElseThrow();

        assertEquals("NOT_INSTALLED", row.effectiveState());
        assertEquals(Optional.of("INSTALL"), row.pendingOperation());
        assertEquals("other", row.category());
        assertEquals(List.of(), row.tags());
    }

    @Test
    void builtInCoreFallbackRowUsesSystemCategory() {
        final CorePluginManagement.PluginInfo core = service().plugins().stream()
            .filter(CorePluginManagement.PluginInfo::core)
            .findFirst().orElseThrow();

        assertEquals("system", core.category());
        assertEquals(List.of(), core.tags());
    }

    @Test
    void pluginInfoTagsAreDefensivelyCopied() {
        final List<String> mutable = new ArrayList<>(List.of("parameter"));
        final CorePluginManagement.PluginInfo row = new CorePluginManagement.PluginInfo(
            "example.plugin", "Example", "1.0.0", "", "ENABLED", "ENABLED", false,
            Optional.empty(), "modeling", mutable
        );

        mutable.add("mutated-after-copy");
        assertNotSame(mutable, row.tags());
        assertEquals(List.of("parameter"), row.tags());
        assertThrows(UnsupportedOperationException.class, () -> row.tags().add("mutated"));
    }

    @Test
    void categoryDefaultsToOtherForNullPresentation() {
        final CorePluginManagement.PluginInfo row = new CorePluginManagement.PluginInfo(
            "example.plugin", "Example", "1.0.0", "", "ENABLED", "ENABLED", false,
            Optional.empty(), null, null
        );
        assertEquals("other", row.category());
        assertEquals(List.of(), row.tags());
    }

    private void installJar(
        final String id,
        final String version,
        final String category,
        final List<String> tags
    ) throws Exception {
        final Path target = home.resolve("plugins").resolve(id + ".jar");
        Files.createDirectories(target.getParent());
        if (category == null) {
            Files.write(target, PluginManagementPackageFixture.pluginJarBytes(id, version));
        } else {
            Files.write(target, PluginManagementPackageFixture.pluginJarBytesV3(id, version, category, tags));
        }
    }

    @Test
    void unknownInstalledCategoryEmitsStructuredDiagnosticThroughServiceSink() throws Exception {
        installJar("unknown.plugin", "1.0.0", "custom-tooling", List.of("local"));
        final List<String> diagnostics = new ArrayList<>();
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
            home, List::of, () -> Locale.ENGLISH, diagnostics::add
        );

        final CorePluginManagement.PluginInfo row = service.plugins().stream()
            .filter(plugin -> plugin.id().equals("unknown.plugin"))
            .findFirst().orElseThrow();

        assertEquals("other", row.category());
        assertEquals(List.of("local"), row.tags());
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).startsWith("PLUGIN_CATEGORY_UNKNOWN: "), diagnostics.get(0));
        assertTrue(diagnostics.get(0).contains("unknown.plugin"), diagnostics.get(0));
        assertTrue(diagnostics.get(0).contains("custom-tooling"), diagnostics.get(0));
    }

    @Test
    void registeredAndV2InstalledCategoriesEmitNoCategoryDiagnostic() throws Exception {
        installJar("v3.plugin", "1.0.0", "modeling", List.of("parameter"));
        installJar("v2.plugin", "1.0.0", null, List.of());
        final List<String> diagnostics = new ArrayList<>();
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
            home, List::of, () -> Locale.ENGLISH, diagnostics::add
        );

        service.plugins();

        assertEquals(List.of(), diagnostics, diagnostics.toString());
    }

    private void writePendingInstall(final String pluginId, final String version) throws Exception {
        final Path journal = home.resolve("state/runtime/plugin-management/pending.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, """
            {
              "format": "turboism.plugin.pending",
              "schemaVersion": 1,
              "operations": [
                {
                  "type": "INSTALL",
                  "pluginId": "%s",
                  "stagedJar": "",
                  "version": "%s",
                  "rawSha256": "",
                  "descriptorSha256": "",
                  "jarSha256": "",
                  "jarSize": 0
                }
              ]
            }
            """.formatted(pluginId, version));
    }

    private CorePluginManagement.PluginInfo row(final String pluginId) {
        final List<CorePluginManagement.PluginInfo> rows = service().plugins().stream()
            .filter(plugin -> plugin.id().equals(pluginId)).toList();
        assertTrue(rows.size() == 1, "expected exactly one row for " + pluginId + " but got " + rows);
        return rows.get(0);
    }

    private RuntimePluginManagementService service() {
        return new RuntimePluginManagementService(home, Optional::empty, List::of);
    }
}
