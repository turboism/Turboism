package dev.turboism.validation.atlasimage.shadow;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

/** Task plugin entrypoint; deliberately has no Instrumentation or agent entrypoint. */
public final class ObserverFreeMeshProbePlugin implements TurboismPlugin {
    @Override public void init(PluginContext context) {
        T040ShadowSceneDriverAgent.startFromPlugin();
        context.logger().info("OBSERVER_FREE_MESH_PLUGIN_STARTED");
    }
    @Override public void enable() {}
    @Override public void shutdown() {}
}
