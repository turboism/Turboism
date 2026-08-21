package dev.turboism.adapter.cubism.mesh;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.sdk.permission.PermissionIds;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshMirrorHookAdmissionTest {
    @Test
    void requiresTheSingleAuthorizedLoadedConsumer() {
        assertFalse(MeshMirrorHookAdmission.admitted(List.of()));
        assertFalse(MeshMirrorHookAdmission.admitted(List.of(plugin(
            PluginLifecycleState.ENABLED, List.of()
        ))));
        final List<String> permissions = List.of(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE
        );
        assertFalse(MeshMirrorHookAdmission.admitted(List.of(plugin(
            PluginLifecycleState.DISABLED, permissions
        ))));
        assertTrue(MeshMirrorHookAdmission.admitted(List.of(plugin(
            PluginLifecycleState.ENABLED, permissions
        ))));
    }

    private static LocalPluginRuntime.LoadedPluginSummary plugin(
        final PluginLifecycleState state,
        final List<String> permissions
    ) {
        return new LocalPluginRuntime.LoadedPluginSummary(
            MeshMirrorHookAdmission.PLUGIN_ID,
            "Mesh",
            "0.1.0",
            state,
            Path.of("mesh-edit-mirror-axis-enhance.jar"),
            List.of("cubism.mesh.mirror-axis-angle"),
            permissions,
            null,
            "NOT_ATTEMPTED",
            "NOT_ATTEMPTED",
            "NOT_ATTEMPTED",
            "NOT_ATTEMPTED",
            "NOT_ATTEMPTED",
            List.of(),
            CleanupEvidenceCollector.Snapshot.empty()
        );
    }
}
