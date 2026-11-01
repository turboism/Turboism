package dev.turboism.plugin.textureatlasstats;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UiScheduler;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Contributes a single-line model-image counter into the native texture-atlas editor
 * window through the framework UI/session capabilities:
 * {@code Total model images: 849, current texture model images: 37}.
 */
public final class TextureAtlasStatisticsPlugin implements TurboismPlugin {

    private PluginContext context;
    private UiScheduler uiScheduler;
    private ScheduledExecutorService scheduler;
    private TextureAtlasEditorPanel panel;
    private volatile boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.uiScheduler = context.uiScheduler();
        // context.logger().info("Texture Atlas Statistics plugin initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (enabled) return;
        enabled = true;
        try {
            final TextureAtlasEditorSession session = context.cubism().textureAtlasEditorSession();
            final TextureAtlasEditorUi editorUi = context.cubism().textureAtlasEditorUi();
            final PluginLocalization i18n = context.localization();
            final TextureAtlasEditorPanel attached = editorUi.attach();
            panel = attached;
            attached.setText(i18n.text("texture-atlas-stats.line"));
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "turboism-texture-atlas-stats");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            final int whole = session.summary()
                                    .map(TextureAtlasSummary::imageCount)
                                    .orElse(0);
                            final int selected = session.selectedTexture()
                                    .map(TextureAtlasSummary::imageCount)
                                    .orElse(0);
                            final String text = i18n.format("texture-atlas-stats.line", whole, selected);
                            dispatchTextToUi(attached, text);
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            dispatchTextToUi(attached, i18n.text("texture-atlas-stats.unavailable"));
                        }
                    },
                    1,
                    1,
                    TimeUnit.SECONDS);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            disable();
            context.logger().warn("Texture Atlas Statistics panel unavailable: " + failure);
        }
    }

    @Override
    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (panel != null) {
            panel.close();
            panel = null;
        }
    }

    @Override
    public void shutdown() {
        disable();
        uiScheduler = null;
        context = null;
    }

    private void dispatchTextToUi(final TextureAtlasEditorPanel target, final String text) {
        if (!enabled) {
            return;
        }
        final UiScheduler ui = uiScheduler;
        if (ui == null) {
            return;
        }
        try {
            ui.runOnUiThread(() -> {
                if (enabled) {
                    target.setText(text);
                }
            });
        } catch (IllegalStateException fenced) {
            // The plugin generation is fenced; the polling executor is shutting down.
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas Statistics plugin must be initialized before enable.");
        }
    }
}
