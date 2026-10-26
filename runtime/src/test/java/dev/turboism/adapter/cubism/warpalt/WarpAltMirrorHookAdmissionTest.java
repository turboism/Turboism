package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.sdk.permission.PermissionIds;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WarpAltMirrorHookAdmissionTest {
    @Test
    void admitsStandaloneArtMeshWithPermissionAndCapability() {
        assertFalse(WarpAltMirrorHookAdmission.admitted(List.of()));
        assertFalse(WarpAltMirrorHookAdmission.admitted(List.of(plugin(PluginLifecycleState.ENABLED, List.of()))));
        final List<String> permissions =
                List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE);
        assertFalse(WarpAltMirrorHookAdmission.admitted(List.of(plugin(PluginLifecycleState.DISABLED, permissions))));
        assertTrue(WarpAltMirrorHookAdmission.admitted(List.of(plugin(PluginLifecycleState.ENABLED, permissions))));
    }

    private static LocalPluginRuntime.LoadedPluginSummary plugin(
            final PluginLifecycleState state, final List<String> permissions) {
        return new LocalPluginRuntime.LoadedPluginSummary(
                "dev.turboism.plugin.artmesh-mirror",
                "Mesh",
                "0.1.0",
                state,
                Path.of("artmesh-mirror.jar"),
                List.of("cubism.artmesh.mirror"),
                permissions,
                null,
                "NOT_ATTEMPTED",
                "NOT_ATTEMPTED",
                "NOT_ATTEMPTED",
                "NOT_ATTEMPTED",
                "NOT_ATTEMPTED",
                List.of(),
                CleanupEvidenceCollector.Snapshot.empty());
    }
}
