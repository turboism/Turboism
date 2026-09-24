package dev.turboism.adapter.cubism.warp;

import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

/** Runtime-internal provider that binds the Warp mirror operation to an owning plugin. */
public interface RuntimeWarpMirrorProvider {

    /**
     * Creates a stable service view for the owning plugin.
     *
     * @param pluginId nonblank owning plugin identity
     * @return service that resolves the current host binding on each invocation
     */
    WarpMirrorService warpMirrorService(String pluginId);
}
