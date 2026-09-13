package dev.turboism.shell;

import dev.turboism.sdk.plugin.PluginDescriptor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellManifestTest {

    @Test
    void descriptorKeepsTheReservedCoreIdentity() {
        final PluginDescriptor descriptor = ShellManifest.descriptor();
        assertEquals(ShellManifest.ID, descriptor.id());
        assertEquals(CorePluginManagement.CORE_PLUGIN_ID, ShellManifest.ID);
    }

    @Test
    void descriptorDeclaresPermissionsForEveryShellSurface() {
        assertEquals(8, ShellManifest.descriptor().permissions().size());
    }

    /**
     * The declared i18n base name and locale list must resolve to real catalog resources on the
     * runtime classpath — the catalogs are read plugin-locally through the shell resource loader,
     * so a declaration that points at nothing degrades every shell label to a missing-key marker.
     */
    @Test
    void declaredI18nCatalogsExistAsResources() {
        final PluginDescriptor.I18n i18n = ShellManifest.descriptor().i18n();
        final Set<String> catalogIds = new LinkedHashSet<>(i18n.locales());
        catalogIds.add("base");
        for (String catalogId : catalogIds) {
            final String path = "base".equals(catalogId)
                ? i18n.baseName() + ".properties"
                : i18n.baseName() + "_" + catalogId.replace('-', '_') + ".properties";
            assertNotNull(
                ShellManifestTest.class.getClassLoader().getResource(path),
                "declared catalog is not a runtime resource: " + path
            );
        }
    }

    @Test
    void descriptorHasNoPluginEntrypoints() {
        assertTrue(ShellManifest.descriptor().entrypoints().isEmpty());
    }
}
