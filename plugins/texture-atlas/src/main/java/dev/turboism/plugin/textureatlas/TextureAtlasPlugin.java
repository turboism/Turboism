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
    static final String ALGORITHM_NATIVE = "native";
    static final String ALGORITHM_MAXRECTS = "maxrects";

    private PluginContext context;
    private boolean enabled;
    private TextureAtlasAutoLayoutService autoLayoutService;
    private javax.swing.Timer statisticsTimer;
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
        context.logger().info("Texture Atlas migration shell initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (!settings.enable().toCompletableFuture().join()) {
            throw new IllegalStateException("Texture Atlas configuration could not be loaded.");
        }
        lifecycle.activate();
        enabled = true;
        System.getProperties().putIfAbsent(NATIVE_AUTO_LAYOUT_CALLBACK_KEY, nativeAutoLayoutCallback);
        registerAlgorithms();
        if (autoLayoutService == null) {
            composeAutoLayoutService();
        }
        publishDialogState();
        attachEditorStatistics();
    }

    /** Registers this plugin's algorithms with the framework registry. */
    private void registerAlgorithms() {
        try {
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry registry =
                context.cubism().textureAtlasAlgorithms();
            final boolean parallel = settings.confirmed().parallel();
            registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
                ALGORITHM_MAXRECTS,
                context.localization().text("texture-atlas.algorithm.maxrects"),
                true,
                (items, constraints) ->
                    new MaxRectsBssfTextureAtlasPlanner().plan(items, constraints, parallel)
            ));
            registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
                ALGORITHM_NATIVE,
                context.localization().text("texture-atlas.algorithm.native"),
                false,
                null
            ));
        } catch (Throwable failure) {
            context.logger().warn("Texture Atlas algorithm registration failed safely: " + failure);
        }
    }

    private void attachEditorStatistics() {
        try {
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession session =
                context.cubism().textureAtlasEditorSession();
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi editorUi =
                context.cubism().textureAtlasEditorUi();
            final javax.swing.JTextArea text = new javax.swing.JTextArea(4, 42);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            final javax.swing.Timer timer = new javax.swing.Timer(1000, event -> {
                try {
                    text.setText(editorStatisticsText(session));
                } catch (Throwable failure) {
                    text.setText("Turboism 纹理统计: " + failure);
                }
            });
            timer.setRepeats(true);
            timer.start();
            editorUi.attach(text);
            statisticsTimer = timer;
        } catch (Throwable failure) {
            context.logger().warn("Texture Atlas editor statistics panel unavailable: " + failure);
        }
    }

    private String editorStatisticsText(
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession session
    ) {
        final dev.turboism.sdk.i18n.PluginLocalization i18n = context.localization();
        final StringBuilder out = new StringBuilder(i18n.text("texture-atlas.stats.title"));
        final var selected = session.selectedTexture();
        if (selected.isPresent()) {
            final var summary = selected.orElseThrow();
            out.append("\n").append(i18n.format("texture-atlas.stats.selected", summary.imageCount()));
            out.append("\n  ").append(sizeDistributionText(summary));
        } else {
            out.append("\n").append(i18n.text("texture-atlas.stats.selected.none"));
        }
        final var whole = session.summary();
        if (whole.isPresent()) {
            final var summary = whole.orElseThrow();
            out.append("\n").append(i18n.format(
                "texture-atlas.stats.whole", summary.imageCount(), summary.pageCount()
            ));
            out.append("\n  ").append(sizeDistributionText(summary));
        }
        return out.toString();
    }

    private String sizeDistributionText(
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary summary
    ) {
        final StringBuilder out = new StringBuilder();
        for (var bucket : summary.sizeDistribution()) {
            if (out.length() > 0) out.append("  ");
            out.append(bucket.width()).append("x").append(bucket.height())
                .append(" ×").append(bucket.count());
        }
        if (out.length() == 0) out.append(context.localization().text("texture-atlas.stats.empty"));
        return out.toString();
    }

    @Override
    public void disable() {
        if (statisticsTimer != null) {
            statisticsTimer.stop();
            statisticsTimer = null;
        }
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
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner planner =
            context.cubism().textureAtlasAlgorithms()
                .find(settings.confirmed().algorithmId())
                .map(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm::planner)
                .orElse(null);
        if (planner == null) {
            // pass-through (native) or unregistered algorithm: delegate to Cubism
            autoLayoutService = null;
            return;
        }
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
            if (TextureAtlasPlugin.ALGORITHM_NATIVE.equals(settings.confirmed().algorithmId())
                || autoLayoutService == null) {
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
        System.getProperties().put(DIALOG_ALGORITHM_KEY, confirmed.algorithmId());
        System.getProperties().put(DIALOG_PARALLEL_KEY, String.valueOf(confirmed.parallel()));
    }

    /** Bridges a dialog change back into the persisted global Turboism configuration. */
    private void syncDialogState() {
        final String algorithm = System.getProperty(DIALOG_ALGORITHM_KEY, "maxrects");
        final boolean parallel = "true".equals(System.getProperty(DIALOG_PARALLEL_KEY, "false"));
        final TextureAtlasSettings confirmed = settings.confirmed();
        if (confirmed.algorithmId().equals(algorithm) && confirmed.parallel() == parallel) {
            return;
        }
        updateSettings(new TextureAtlasSettings(
            confirmed.layoutMode(), algorithm, parallel
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
