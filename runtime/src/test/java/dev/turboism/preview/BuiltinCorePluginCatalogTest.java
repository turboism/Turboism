package dev.turboism.preview;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.plugin.core.MainToolbarPlugin;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URLClassLoader;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinCorePluginCatalogTest {
    @Test void loadsDeclaredCoreCatalogsFromTheAgentArtifactOwner() throws Exception {
        final ClassLoader applicationLoader = MainToolbarPlugin.class.getClassLoader();
        final PluginDescriptor descriptor;
        try (InputStream input = applicationLoader.getResourceAsStream("META-INF/turboism/core-plugin.json")) {
            descriptor = new PluginDescriptorParser().parse(input);
        }

        try (URLClassLoader resources = BuiltinCorePlugin.resourceLoader(applicationLoader)) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                descriptor.id(), resources, descriptor.i18n(), "en", Locale.ENGLISH, Locale.ENGLISH,
                diagnostic -> { throw new AssertionError(diagnostic.code() + ": " + diagnostic.message()); }
            );

            assertEquals(
                java.util.stream.Stream.concat(
                    descriptor.i18n().locales().stream(),
                    java.util.stream.Stream.of("base")
                ).distinct().toList(),
                localization.reportSnapshot().catalogs().stream()
                    .map(RuntimePluginLocalization.CatalogSnapshot::locale).toList()
            );
            assertTrue(localization.reportSnapshot().catalogs().stream()
                .allMatch(catalog -> catalog.state().equals("AVAILABLE")));
            assertEquals("Plugin Management", localization.text("main-toolbar.plugins-menu.label"));
            assertEquals("Settings", localization.text("main-toolbar.settings-menu.label"));
            assertEquals("Turboism settings", localization.text("main-toolbar.home.aria-label"));
            assertEquals("Open Turboism settings", localization.text("main-toolbar.home.tooltip"));
        }
    }

}
