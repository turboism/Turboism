package dev.turboism.plugin.textureatlas;

import dev.turboism.plugin.textureatlas.layout.PartBucketTextureAtlasPlanner;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** Composes plugin-owned layout policy with the SDK authoring seam; native UI remains separate. */
public final class TextureAtlasPlugin implements TurboismPlugin {

    private PluginContext context;
    private boolean enabled;
    private TextureAtlasAutoLayoutService autoLayoutService;
    private TextureAtlasAutoLayoutService.LifecycleLease lifecycle;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.lifecycle = new TextureAtlasAutoLayoutService.LifecycleLease();
        this.autoLayoutService = new TextureAtlasAutoLayoutService(
            context.cubism().textureAtlasLayouts(),
            new PartBucketTextureAtlasPlanner(),
            lifecycle
        );
        context.logger().info("Texture Atlas migration shell initialized");
    }

    @Override
    public void enable() {
        requireContext();
        lifecycle.activate();
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        if (lifecycle != null) lifecycle.deactivate();
    }

    @Override
    public void shutdown() {
        enabled = false;
        if (lifecycle != null) lifecycle.close();
        context = null;
        autoLayoutService = null;
        lifecycle = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    TextureAtlasAutoLayoutService autoLayoutService() {
        requireContext();
        if (!enabled) throw new IllegalStateException("Texture Atlas plugin must be enabled before use.");
        return autoLayoutService;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas migration shell must be initialized before enable.");
        }
    }
}
