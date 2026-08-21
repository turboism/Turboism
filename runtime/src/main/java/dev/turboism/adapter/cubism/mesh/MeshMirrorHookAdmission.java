package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.List;

/** Runtime-owned admission for the single production mesh-mirror hook consumer. */
public final class MeshMirrorHookAdmission {
    public static final String PLUGIN_ID = "dev.turboism.plugin.mesh-edit-mirror-axis-enhance";

    private MeshMirrorHookAdmission() { }

    public static boolean admitted(final List<LocalPluginRuntime.LoadedPluginSummary> plugins) {
        return plugins.stream().anyMatch(plugin ->
            plugin.id().equals(PLUGIN_ID)
                && plugin.state() == PluginLifecycleState.ENABLED
                && plugin.permissionIds().contains(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)
                && plugin.permissionIds().contains(PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE)
                && plugin.capabilities().contains("cubism.mesh.mirror-axis-angle")
        );
    }
}
