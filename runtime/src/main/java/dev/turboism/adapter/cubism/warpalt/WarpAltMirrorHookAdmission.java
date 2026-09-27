package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.List;

/** Runtime-owned admission for the single production warp Alt-mirror hook consumer. */
public final class WarpAltMirrorHookAdmission {
    public static final String PLUGIN_ID = "dev.turboism.plugin.warp-deformer-alt-symmetry";

    private WarpAltMirrorHookAdmission() { }

    /**
     * Decides whether the warp Alt-mirror host hook may be bound for this session.
     *
     * <p>Admission is deliberately narrow: exactly one plugin id ({@link #PLUGIN_ID}) is
     * eligible, and only while it is enabled, holds the model-write permission, and
     * declares the {@code cubism.deformer.alt-axis-mirror} capability.</p>
     *
     * @param plugins the currently loaded plugin summaries to search
     * @return {@code true} only when a summary satisfies every one of those conditions
     */
    public static boolean admitted(final List<LocalPluginRuntime.LoadedPluginSummary> plugins) {
        return plugins.stream().anyMatch(plugin ->
            plugin.id().equals(PLUGIN_ID)
                && plugin.state() == PluginLifecycleState.ENABLED
                && plugin.permissionIds().contains(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)
                && plugin.capabilities().contains("cubism.deformer.alt-axis-mirror")
        );
    }
}
