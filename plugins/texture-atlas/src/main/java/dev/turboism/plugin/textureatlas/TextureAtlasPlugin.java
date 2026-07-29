package dev.turboism.plugin.textureatlas;

import dev.turboism.plugin.textureatlas.layout.PartBucketTextureAtlasPlanner;
import dev.turboism.plugin.textureatlas.layout.MaxRectsBssfTextureAtlasPlanner;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** Composes plugin-owned layout policy with the SDK authoring seam; native UI remains separate. */
public final class TextureAtlasPlugin implements TurboismPlugin {

    private PluginContext context;
    private boolean enabled;
    private TextureAtlasAutoLayoutService autoLayoutService;
    private TextureAtlasAutoLayoutService.LifecycleLease lifecycle;
    private final TextureAtlasSettingsBinding settings = new TextureAtlasSettingsBinding();

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.lifecycle = new TextureAtlasAutoLayoutService.LifecycleLease();
        settings.init(context.config()).toCompletableFuture().join();
        composeAutoLayoutService();
        context.logger().info("Texture Atlas migration shell initialized");
    }

    @Override
    public void enable() {
        requireContext();
        settings.enable().toCompletableFuture().join();
        if (autoLayoutService == null) composeAutoLayoutService();
        lifecycle.activate();
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        settings.disable();
        if (lifecycle != null) lifecycle.deactivate();
    }

    @Override
    public void shutdown() {
        enabled = false;
        settings.shutdown();
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

    TextureAtlasSettings settings() {
        requireContext();
        return settings.confirmed();
    }

    boolean updateSettings(final TextureAtlasSettings value) {
        requireContext();
        final boolean written = settings.update(value).toCompletableFuture().join();
        if (written) composeAutoLayoutService();
        return written;
    }

    private void composeAutoLayoutService() {
        final TextureAtlasLayoutPlanner planner = settings.confirmed().layoutMode()
            == TextureAtlasLayoutMode.COMPACT
            ? new MaxRectsBssfTextureAtlasPlanner()::plan
            : new PartBucketTextureAtlasPlanner()::plan;
        autoLayoutService = new TextureAtlasAutoLayoutService(
            context.cubism().textureAtlasLayouts(),
            planner,
            lifecycle
        );
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas migration shell must be initialized before enable.");
        }
    }
}
