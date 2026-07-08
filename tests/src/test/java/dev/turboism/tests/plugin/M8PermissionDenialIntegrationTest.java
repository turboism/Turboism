package dev.turboism.tests.plugin;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.test.plugin.PermissionProbePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class M8PermissionDenialIntegrationTest {

    @Test
    void actionMenuToolbarAndConfigRegistrationsAreDeniedAndFailuresAreRecorded(@TempDir Path dataDir) throws Exception {
        // Given
        PermissionChecker denyingPermissions = (permissionId, operation) -> {
            throw new CubismPermissionException(operation + " denied");
        };
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(dataDir, denyingPermissions)) {
            PermissionProbePlugin plugin = new PermissionProbePlugin();
            plugin.init(harness.context());

            // When
            plugin.enable();

            // Then
            assertEquals(4, plugin.failures().size());
            plugin.failures().forEach(failure -> assertInstanceOf(CubismPermissionException.class, failure));
            assertEquals(0, plugin.actionRegistrationCount());
            assertEquals(0, plugin.menuRegistrationCount());
            assertEquals(0, plugin.toolbarRegistrationCount());
            assertEquals(0, plugin.configRegistrationCount());
            assertFalse(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertFalse(harness.menuTracker().isVisible("probe.action"));
        }
    }
}
