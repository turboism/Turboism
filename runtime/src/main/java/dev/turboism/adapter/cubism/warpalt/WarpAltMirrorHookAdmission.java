package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.lifecycle.PluginAdmissionView;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.sdk.permission.PermissionIds;
import java.util.List;

/** Runtime-owned admission for the shared Warp Deformer and ArtMesh mirror hooks. */
public final class WarpAltMirrorHookAdmission {
    public static final String PLUGIN_ID = "dev.turboism.plugin.warp-deformer-alt-symmetry";

    private WarpAltMirrorHookAdmission() {}

    /**
     * Decides whether the warp Alt-mirror host hook may be bound for this session.
     *
     * <p>Either dedicated mirror plugin may request the shared hooks while enabled,
     * holding model-write permission and declaring its own mirror capability.</p>
     *
     * @param plugins the currently loaded plugin summaries to search
     * @return {@code true} only when a summary satisfies every one of those conditions
     */
    public static boolean admitted(final List<? extends PluginAdmissionView> plugins) {
        return plugins.stream()
                .anyMatch(plugin -> ((plugin.id().equals(PLUGIN_ID)
                                        && plugin.capabilities().contains("cubism.deformer.alt-axis-mirror"))
                                || (plugin.id().equals("dev.turboism.plugin.artmesh-mirror")
                                        && plugin.capabilities().contains("cubism.artmesh.mirror")))
                        && plugin.state() == PluginLifecycleState.ENABLED
                        && plugin.permissionIds().contains(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE));
    }
}
