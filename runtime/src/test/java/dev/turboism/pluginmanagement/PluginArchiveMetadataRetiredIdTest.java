package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retired fake plugin ids must never surface through the plugin-management
 * metadata route either: {@link PluginArchiveMetadata} (installed-plugin
 * listing, pending-install matching) denies them with the same shared
 * {@link dev.turboism.core.plugin.PluginJarContract#RETIRED_PLUGIN_IDS} set
 * that the loader contract enforces, so a leftover retired JAR is neither
 * listed nor matched, on any distribution path.
 */
class PluginArchiveMetadataRetiredIdTest {

    private static final String RETIRED_ID = "dev.turboism.plugin.logfilter";
    private static final String SUCCESSOR_ID = "dev.turboism.plugin.clipmask-viewer";

    @TempDir
    Path home;

    @Test
    void retiredFakeIdsAreNeitherReadNorListedWhileRetainedIdsRemain() throws Exception {
        final Path plugins = Files.createDirectories(home.resolve("plugins"));
        Files.write(plugins.resolve("renamed-retired.jar"), archive(RETIRED_ID));
        Files.write(plugins.resolve("clipmask-viewer.jar"), archive(SUCCESSOR_ID));

        assertTrue(PluginArchiveMetadata.read(plugins.resolve("renamed-retired.jar")).isEmpty(),
            "retired id must not yield archive metadata");
        assertTrue(PluginArchiveMetadata.read(plugins.resolve("clipmask-viewer.jar")).isPresent(),
            "retained successor id must still yield archive metadata");

        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
            home, List::of, () -> Locale.ENGLISH, ignored -> { }
        );
        final List<String> listed = service.plugins().stream()
            .map(RuntimePluginManagementService.PluginInfo::id)
            .filter(id -> !dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID.equals(id))
            .toList();
        assertFalse(listed.contains(RETIRED_ID), "retired id must not be listed: " + listed);
        assertTrue(listed.contains(SUCCESSOR_ID), "retained successor id must be listed: " + listed);
    }

    private static byte[] archive(final String id) throws Exception {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("META-INF/turboism/plugin.json"));
            jar.write(descriptor(id));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/turboism/i18n/messages.properties"));
            jar.write("plugin.name=Fixture\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] descriptor(final String id) {
        return ("{\n"
            + "  \"format\": \"turboism.plugin.meta\",\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"id\": \"" + id + "\",\n"
            + "  \"name\": \"Fixture\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"description\": \"Fixture.\",\n"
            + "  \"entrypoints\": [\"dev.example.FixturePlugin\"],\n"
            + "  \"turboismApi\": \"[0.1.0,0.2.0)\",\n"
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
            + "}\n").getBytes(StandardCharsets.UTF_8);
    }
}
