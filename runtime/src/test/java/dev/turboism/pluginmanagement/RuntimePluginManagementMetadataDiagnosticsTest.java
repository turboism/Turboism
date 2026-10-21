package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CorePluginManagement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production installed-plugin metadata i18n diagnostics must reach a real sink
 * (P1-1 remediation): malformed i18n metadata in an installed archive reports a
 * structured diagnostic instead of disappearing behind a no-op lambda.
 */
class RuntimePluginManagementMetadataDiagnosticsTest {

    @TempDir
    Path home;

    @Test
    void malformedInstalledArchiveI18nMetadataReachesTheDiagnosticSink() throws Exception {
        final Path plugins = Files.createDirectories(home.resolve("plugins"));
        Files.write(
                plugins.resolve("broken-plugin.jar"),
                archive(Map.of(
                        "META-INF/turboism/plugin.json", descriptor("broken-plugin", "Broken Plugin"),
                        "META-INF/turboism/i18n/messages_en.properties",
                                ("plugin.name=Broken Plugin\n" + "plugin.name=Broken Plugin Duplicate\n")
                                        .getBytes(StandardCharsets.UTF_8))));

        final List<String> diagnostics = new ArrayList<>();
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
                home, List::of, () -> Locale.ENGLISH, diagnostics::add);

        service.plugins();

        assertEquals(1, diagnostics.size(), "the malformed catalog must produce one diagnostic");
        assertTrue(diagnostics.get(0).contains("I18N_CATALOG_DUPLICATE_KEY"), diagnostics.get(0));
        assertTrue(diagnostics.get(0).contains("Catalog contains a duplicate localization key"), diagnostics.get(0));
    }

    @Test
    void missingInstalledArchiveI18nMetadataFallsBackGracefullyWithoutSuppressingDiagnostics() throws Exception {
        final Path plugins = Files.createDirectories(home.resolve("plugins"));
        // Descriptor declares an i18n block, but no catalog resources exist.
        Files.write(
                plugins.resolve("bare-plugin.jar"),
                archive(Map.of("META-INF/turboism/plugin.json", descriptor("bare-plugin", "Bare Plugin"))));

        final List<String> diagnostics = new ArrayList<>();
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
                home, List::of, () -> Locale.ENGLISH, diagnostics::add);

        List<CorePluginManagement.PluginInfo> pluginsList = service.plugins();

        // Missing catalogs must not crash discovery; the descriptor values are the fallback.
        assertTrue(pluginsList.stream().anyMatch(info -> info.id().equals("dev.turboism.plugin.bare-plugin")));
        assertEquals(
                "Bare Plugin",
                pluginsList.stream()
                        .filter(info -> info.id().equals("dev.turboism.plugin.bare-plugin"))
                        .findFirst()
                        .orElseThrow()
                        .name());
        assertTrue(
                diagnostics.isEmpty(), "missing catalogs alone stay silent; malformed content is the diagnostic path");
    }

    @Test
    void installedDetailsExposeDeclaredCubismVersionsAndPartialFunctionality() throws Exception {
        final CorePluginManagement.PluginDetails details = installedDetails("""
                {"format":"turboism.plugin-cubism-versions/v1","plugin":"dev.turboism.plugin.toy",
                 "supportedVersions":["5.0.00","5.2.00"],"fullFeatureVersions":["5.2.00"]}
                """);
        final CorePluginManagement.CubismCompatibility compatibility =
                details.cubismCompatibility().orElseThrow();
        assertEquals(List.of("5.0.00", "5.2.00"), compatibility.supportedVersions());
        assertEquals(List.of("5.2.00"), compatibility.fullFeatureVersions().orElseThrow());
        assertEquals("[0.67.0,1.0.0)", details.turboismApi());
        assertTrue(details.readme().orElseThrow().contains("packaged README"));
    }

    @Test
    void missingOrInvalidCubismDeclarationDoesNotHidePluginOrInventSupport() throws Exception {
        for (String sidecar : List.of("", "{broken", """
                {"format":"turboism.plugin-cubism-versions/v1","plugin":"another.plugin",
                 "supportedVersions":["5.0.00"],"fullFeatureVersions":["5.0.00"]}
                """, """
                {"format":"turboism.plugin-cubism-versions/v1","plugin":"dev.turboism.plugin.toy",
                 "supportedVersions":["5.0.00"],"fullFeatureVersions":["5.2.00"]}
                """)) {
            final CorePluginManagement.PluginDetails details = installedDetails(sidecar);
            assertTrue(details.cubismCompatibility().isEmpty(), sidecar);
            assertEquals("dev.turboism.plugin.toy", details.plugin().id());
        }
    }

    @Test
    void supportedOnlyDeclarationDoesNotClaimCompleteFeatures() throws Exception {
        final CorePluginManagement.PluginDetails details = installedDetails("""
                {"format":"turboism.plugin-cubism-versions/v1","plugin":"dev.turboism.plugin.toy",
                 "supportedVersions":["5.0.00"]}
                """);
        assertEquals(
                List.of("5.0.00"), details.cubismCompatibility().orElseThrow().supportedVersions());
        assertTrue(details.cubismCompatibility()
                .orElseThrow()
                .fullFeatureVersions()
                .isEmpty());
    }

    private CorePluginManagement.PluginDetails installedDetails(final String sidecar) throws Exception {
        final Path plugins = Files.createDirectories(home.resolve("plugins"));
        final Map<String, byte[]> entries = new java.util.HashMap<>();
        entries.put(
                "META-INF/turboism/plugin.json",
                new String(descriptor("toy", "Toy"), StandardCharsets.UTF_8)
                        .replace("\"requiresCubism\": false", "\"requiresCubism\": true")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/turboism/readme/README.md", "# packaged README".getBytes(StandardCharsets.UTF_8));
        if (!sidecar.isEmpty())
            entries.put("META-INF/turboism/cubism-versions.json", sidecar.getBytes(StandardCharsets.UTF_8));
        Files.write(plugins.resolve("toy.jar"), archive(entries));
        return RuntimePluginManagementService.withMetadataLocale(home, List::of, () -> Locale.ENGLISH, ignored -> {})
                .details("dev.turboism.plugin.toy")
                .orElseThrow();
    }

    private static byte[] archive(final Map<String, byte[]> entries) throws Exception {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] descriptor(final String id, final String name) {
        return ("{\n"
                        + "  \"format\": \"turboism.plugin.meta\",\n"
                        + "  \"schemaVersion\": 2,\n"
                        + "  \"id\": \"dev.turboism.plugin." + id + "\",\n"
                        + "  \"name\": \"" + name + "\",\n"
                        + "  \"version\": \"1.0.0\",\n"
                        + "  \"description\": \"" + name + " for metadata diagnostics.\",\n"
                        + "  \"entrypoints\": [\"dev.turboism.plugin." + id.replace("-", "") + ".Plugin\"],\n"
                        + "  \"turboismApi\": \"[0.67.0,1.0.0)\",\n"
                        + "  \"authors\": [{\"name\": \"Turboism Contributors\"}],\n"
                        + "  \"license\": \"Project License\",\n"
                        + "  \"website\": \"https://turboism.dev\",\n"
                        + "  \"resources\": [],\n"
                        + "  \"i18n\": {\"baseName\": \"META-INF/turboism/i18n/messages\","
                        + " \"locales\": [\"en\", \"ja\", \"ko\", \"zh-Hans\", \"zh-Hant\"]},\n"
                        + "  \"dependencies\": [],\n"
                        + "  \"permissions\": [],\n"
                        + "  \"capabilities\": [],\n"
                        + "  \"environment\": {\"requiresCubism\": false, \"ui\": \"none\"}\n"
                        + "}\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
