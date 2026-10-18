package dev.turboism.plugin.textureatlasstats;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Contributes a single-line model-image counter into the native texture-atlas editor
 * window through the framework UI/session capabilities:
 * {@code Total model images: 849, current texture model images: 37}.
 */
public final class TextureAtlasStatisticsPlugin extends AbstractTurboismPlugin {

    private ScheduledExecutorService scheduler;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        try {
            super.init(context);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Texture Atlas Statistics plugin initialization failed", e);
        }
        // context.logger().info("Texture Atlas Statistics plugin initialized");
    }

    @Override
    public void enable() {
        try {
            super.enable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Texture Atlas Statistics plugin must be initialized before enable.", e);
        }
        if (enabled) return;
        enabled = true;
        try {
            final TextureAtlasEditorSession session = context().cubism().textureAtlasEditorSession();
            final TextureAtlasEditorUi editorUi = context().cubism().textureAtlasEditorUi();
            final PluginLocalization i18n = context().localization();
            final TextureAtlasEditorPanel attached = editorUi.attach();
            disposalScope().register(attached);
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
                            SwingUtilities.invokeLater(() -> attached.setText(text));
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            final String unavailable = i18n.text("texture-atlas-stats.unavailable");
                            SwingUtilities.invokeLater(() -> attached.setText(unavailable));
                        }
                    },
                    1,
                    1,
                    TimeUnit.SECONDS);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            disable();
            try {
                context().logger().warn("Texture Atlas Statistics panel unavailable: " + failure);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @Override
    public void disable() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            super.disable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Texture Atlas Statistics plugin disable failed", e);
        }
        enabled = false;
    }

    @Override
    public void shutdown() {
        disable();
        try {
            super.shutdown();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Texture Atlas Statistics plugin shutdown failed", e);
        }
    }

    private PluginContext requireContext() {
        return context();
    }
}
