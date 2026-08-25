package dev.turboism.pluginmanagement;

import dev.turboism.plugin.core.CorePluginManagement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDetailsTest {
    @TempDir Path home;

    @Test
    void installedArchiveExposesDescriptorMetadataAndReadme() throws Exception {
        install("detailed.plugin", PluginManagementPackageFixture.detailedPluginJarBytes(
            "detailed.plugin", "1.2.3"
        ));
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
            home, List::of, () -> Locale.ENGLISH
        );

        final CorePluginManagement.PluginDetails details = service.details("detailed.plugin").orElseThrow();

        assertEquals("detailed.plugin", details.plugin().id());
        assertEquals("[0.1.0,0.2.0)", details.turboismApi());
        assertEquals(List.of(new CorePluginManagement.Author(
            "Test Author", Optional.of("test@example.test")
        )), details.authors());
        assertEquals("MIT", details.license());
        assertEquals(Optional.of("https://example.test/plugin"), details.website());
        assertEquals("required.plugin", details.dependencies().get(0).id());
        assertEquals("turboism.action.register", details.permissions().get(0).id());
        assertEquals(List.of("test.capability"), details.capabilities());
        assertTrue(details.requiresCubism());
        assertEquals("swing", details.ui());
        assertEquals(List.of("example.Plugin"), details.entrypoints());
        assertEquals(List.of(), details.resources());
        assertEquals("META-INF/turboism/i18n/messages", details.i18nBaseName());
        assertEquals(List.of(), details.locales());
        assertEquals(List.of(), details.eventExports());
        assertEquals(List.of(), details.eventImports());
        assertTrue(details.readme().orElseThrow().contains("Rendered **README**"));
    }

    @Test
    void missingReadmeReturnsEmptyWithoutLosingMetadata() throws Exception {
        install("plain.plugin", PluginManagementPackageFixture.pluginJarBytes("plain.plugin", "1.0.0"));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, Optional::empty, List::of
        );

        final CorePluginManagement.PluginDetails details = service.details("plain.plugin").orElseThrow();

        assertEquals("Test", details.license());
        assertFalse(details.readme().isPresent());
    }

    @Test
    void oversizedReadmeIsRejected() throws Exception {
        final String readme = "x".repeat(PluginArchiveMetadata.MAX_README_BYTES + 1);
        install("large.plugin", PluginManagementPackageFixture.pluginJarBytesWithReadme(
            "large.plugin", "1.0.0", readme
        ));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, Optional::empty, List::of
        );

        assertFalse(service.details("large.plugin").orElseThrow().readme().isPresent());
    }

    @Test
    void unknownPluginHasNoDetails() {
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, Optional::empty, List::of
        );

        assertTrue(service.details("missing.plugin").isEmpty());
        assertTrue(service.details(" ").isEmpty());
    }

    @Test
    void builtInCoreExposesBundledDescriptorAndReadme() {
        final RuntimePluginManagementService service = RuntimePluginManagementService.withMetadataLocale(
            home, List::of, () -> Locale.ENGLISH
        );

        final CorePluginManagement.PluginDetails details = service.details(
            CorePluginManagement.CORE_PLUGIN_ID
        ).orElseThrow();

        assertTrue(details.plugin().core());
        assertEquals("Project License", details.license());
        assertEquals(Optional.of("https://turboism.dev"), details.website());
        assertTrue(details.readme().orElseThrow().contains("# Turboism Core"));
    }

    private void install(final String id, final byte[] jar) throws Exception {
        final Path path = home.resolve("plugins").resolve(id + ".jar");
        Files.createDirectories(path.getParent());
        Files.write(path, jar);
    }
}
