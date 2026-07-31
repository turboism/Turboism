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
    static final String DIALOG_ALGORITHM_KEY = "dev.turboism.texture-atlas.dialog.algorithm";
    static final String DIALOG_PARALLEL_KEY = "dev.turboism.texture-atlas.dialog.parallel";

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
        System.getProperties().putIfAbsent(NATIVE_AUTO_LAYOUT_CALLBACK_KEY, nativeAutoLayoutCallback);
        publishDialogState();
    }

    @Override
    public void disable() {
        removeNativeCallback();
        enabled = false;
        settings.disable();
        if (lifecycle != null) lifecycle.deactivate();
    }

    @Override
    public void shutdown() {
        removeNativeCallback();
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
        final boolean parallel = settings.confirmed().parallel();
        final TextureAtlasLayoutPlanner planner = settings.confirmed().layoutMode()
            == TextureAtlasLayoutMode.COMPACT
            ? (items, constraints) -> new MaxRectsBssfTextureAtlasPlanner().plan(items, constraints, parallel)
            : new PartBucketTextureAtlasPlanner()::plan;
        autoLayoutService = new TextureAtlasAutoLayoutService(
            context.cubism().textureAtlasLayouts(),
            planner,
            lifecycle,
            message -> context.logger().info(message)
        );
    }

    private boolean applyFromNativeEntry() {
        try {
            syncDialogState();
            if (settings.confirmed().algorithm() == TextureAtlasLayoutAlgorithm.NATIVE) {
                context.logger().info("Texture Atlas automatic layout delegated to Cubism native algorithm");
                return false;
            }
            final TextureAtlasLayoutApplyResult result = autoLayoutService().applyAutomaticLayout();
            if (result.status().isPresent()) {
                context.logger().info(
                    "Texture Atlas native automatic-layout result status=" + result.status().orElseThrow()
                );
                return true;
            }
            context.logger().warn(
                "Texture Atlas native automatic-layout result failureCode="
                    + result.failureCode().orElseThrow()
            );
            return false;
        } catch (RuntimeException | Error failure) {
            if (context != null) {
                context.logger().error("Texture Atlas native automatic-layout entry failed safely.", failure);
            }
            return false;
        }
    }

    /** Publishes the persisted policy to the runtime dialog ingress so the dialog restores it. */
    private void publishDialogState() {
        final TextureAtlasSettings confirmed = settings.confirmed();
        System.getProperties().put(
            DIALOG_ALGORITHM_KEY,
            confirmed.algorithm() == TextureAtlasLayoutAlgorithm.NATIVE ? "native" : "maxrects"
        );
        System.getProperties().put(DIALOG_PARALLEL_KEY, String.valueOf(confirmed.parallel()));
    }

    /** Bridges a dialog change back into the persisted global Turboism configuration. */
    private void syncDialogState() {
        final String algorithm = System.getProperty(DIALOG_ALGORITHM_KEY, "maxrects");
        final boolean parallel = "true".equals(System.getProperty(DIALOG_PARALLEL_KEY, "false"));
        final TextureAtlasSettings confirmed = settings.confirmed();
        final TextureAtlasLayoutAlgorithm selected = "native".equals(algorithm)
            ? TextureAtlasLayoutAlgorithm.NATIVE
            : TextureAtlasLayoutAlgorithm.MAXRECTS;
        if (confirmed.algorithm() == selected && confirmed.parallel() == parallel) {
            return;
        }
        updateSettings(new TextureAtlasSettings(
            confirmed.layoutMode(), selected, parallel
        ));
    }

    private void removeNativeCallback() {
        final Object value = System.getProperties().get(NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        if (value == nativeAutoLayoutCallback) {
            System.getProperties().remove(NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas migration shell must be initialized before enable.");
        }
    }
}
