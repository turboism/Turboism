package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArchiveSummaryTest {
    @TempDir
    Path home;

    @Test
    void listingKeepsMetadataButLeavesDocumentationToTheDetailsRead() throws Exception {
        final Path jar = Files.write(
                home.resolve("plugin.jar"),
                PluginManagementPackageFixture.pluginJarBytesWithReadme("dev.example", "1.0.0", "# Documentation"));
        final var summary = PluginArchiveMetadata.readSummary(jar, Locale.ENGLISH, ignored -> {})
                .orElseThrow();
        final var details =
                PluginArchiveMetadata.read(jar, Locale.ENGLISH, ignored -> {}).orElseThrow();
        assertEquals(details.id(), summary.id());
        assertEquals(details.name(), summary.name());
        assertEquals(details.version(), summary.version());
        assertEquals(details.description(), summary.description());
        assertEquals(details.category(), summary.category());
        assertEquals(details.tags(), summary.tags());
        assertEquals(details.authors(), summary.authors());
        assertTrue(summary.readme().isEmpty());
        assertTrue(summary.cubismCompatibility().isEmpty());
        assertEquals("# Documentation", details.readme().orElseThrow());
    }
}
