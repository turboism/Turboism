package dev.turboism.plugin.textureatlas;

import dev.turboism.plugin.textureatlas.layout.PartBucketTextureAtlasPlanner;
import dev.turboism.plugin.textureatlas.layout.MaxRectsBssfTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Composes plugin-owned layout policy with the SDK authoring seam and native automatic-layout entry. */
public final class TextureAtlasPlugin implements TurboismPlugin {

    static final String NATIVE_AUTO_LAYOUT_CALLBACK_KEY =
        "dev.turboism.texture-atlas.auto-layout.callback";

    private PluginContext context;
    private boolean enabled;
    private TextureAtlasAutoLayoutService autoLayoutService;
    private TextureAtlasAutoLayoutService.LifecycleLease lifecycle;
    private final TextureAtlasSettingsBinding settings = new TextureAtlasSettingsBinding();
    private final BooleanSupplier nativeAutoLayoutCallback = this::applyFromNativeEntry;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.lifecycle = new TextureAtlasAutoLayoutService.LifecycleLease();
        if (!settings.init(context.config()).toCompletableFuture().join()) {
            throw new IllegalStateException("Texture Atlas configuration schema registration failed.");
        }
        composeAutoLayoutService();
        context.logger().info("Texture Atlas migration shell initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (!settings.enable().toCompletableFuture().join()) {
            throw new IllegalStateException("Texture Atlas configuration could not be loaded.");
        }
        if (autoLayoutService == null) composeAutoLayoutService();
        lifecycle.activate();
        enabled = true;
        System.getProperties().put(NATIVE_AUTO_LAYOUT_CALLBACK_KEY, nativeAutoLayoutCallback);
    }

    @Override
    public void disable() {
        System.getProperties().remove(NATIVE_AUTO_LAYOUT_CALLBACK_KEY, nativeAutoLayoutCallback);
        enabled = false;
        settings.disable();
        if (lifecycle != null) lifecycle.deactivate();
    }

    @Override
    public void shutdown() {
        System.getProperties().remove(NATIVE_AUTO_LAYOUT_CALLBACK_KEY, nativeAutoLayoutCallback);
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

    private boolean applyFromNativeEntry() {
        try {
            final TextureAtlasLayoutApplyResult result = autoLayoutService().applyAutomaticLayout();
            return result.status().isPresent();
        } catch (RuntimeException | Error failure) {
            if (context != null) {
                context.logger().error("Texture Atlas native automatic-layout entry failed safely.", failure);
            }
            return false;
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas migration shell must be initialized before enable.");
        }
    }
}
