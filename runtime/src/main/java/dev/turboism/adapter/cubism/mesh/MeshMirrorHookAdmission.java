package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.List;

/** Runtime-owned admission for the single production mesh-mirror hook consumer. */
public final class MeshMirrorHookAdmission {
    public static final String PLUGIN_ID = "dev.turboism.plugin.mesh";

    private MeshMirrorHookAdmission() { }

    /**
     * Decides whether the mesh-mirror host hooks may be installed at all.
     *
     * <p>Admission is deliberately narrow: exactly one plugin id ({@link #PLUGIN_ID}) is eligible,
     * and only while it is enabled, holds both the model-write and panel-contribute permissions, and
     * declares the {@code cubism.mesh.mirror-axis-angle} capability. Any other plugin, however
     * permissioned, is never admitted.
     *
     * @param plugins the currently loaded plugin summaries to search
     * @return {@code true} only when a summary satisfies every one of those conditions
     */
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
