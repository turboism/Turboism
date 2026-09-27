package dev.turboism.preview;

import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreShellRuntimeCatalogTest {
    @Test void loadsDeclaredCoreCatalogsFromTheAgentArtifactOwner() throws Exception {
        final PluginDescriptor descriptor = ShellManifest.descriptor();
        final var factory = CoreShellRuntime.class.getDeclaredMethod("shellResourceLoader");
        factory.setAccessible(true);
        try (URLClassLoader resources = (URLClassLoader) factory.invoke(null)) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                descriptor.id(), resources, descriptor.i18n(), "en", Locale.ENGLISH, Locale.ENGLISH,
                diagnostic -> { throw new AssertionError(diagnostic.code() + ": " + diagnostic.message()); }
            );

            final java.util.ArrayList<String> expectedCatalogs = new java.util.ArrayList<>(
                descriptor.i18n().locales()
            );
            expectedCatalogs.add("base");
            assertEquals(expectedCatalogs, localization.reportSnapshot().catalogs().stream()
                .map(RuntimePluginLocalization.CatalogSnapshot::locale).toList());
            assertTrue(localization.reportSnapshot().catalogs().stream()
                .allMatch(catalog -> catalog.state().equals("AVAILABLE")));
            assertEquals("Plugin Management", localization.text("main-toolbar.plugins-menu.label"));
            assertEquals("Settings", localization.text("main-toolbar.settings-menu.label"));
            assertEquals("Turboism settings", localization.text("main-toolbar.home.aria-label"));
            assertEquals("Open Turboism settings", localization.text("main-toolbar.home.tooltip"));
        }
    }

}
