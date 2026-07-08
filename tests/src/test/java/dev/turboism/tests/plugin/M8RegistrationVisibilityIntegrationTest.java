package dev.turboism.tests.plugin;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.test.plugin.PermissionProbePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M8RegistrationVisibilityIntegrationTest {

    @Test
    void contributionsAreVisibleAfterEnableAndHiddenAfterDisable(@TempDir Path dataDir) throws Exception {
        // Given
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(dataDir, PermissionChecker.allowAll())) {
            PermissionProbePlugin plugin = new PermissionProbePlugin();
            plugin.init(harness.context());

            // When
            plugin.enable();

            // Then
            assertTrue(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertTrue(harness.menuTracker().isVisible("probe.action"));

            // When
            plugin.disable();

            // Then
            assertFalse(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertFalse(harness.menuTracker().isVisible("probe.action"));
        }
    }
}
