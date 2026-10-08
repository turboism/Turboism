package dev.turboism.plugin.textureatlasstats;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Contributes a single-line model-image counter into the native texture-atlas editor
 * window through the framework UI/session capabilities:
 * {@code Total model images: 849, current texture model images: 37}.
 */
public final class TextureAtlasStatisticsPlugin implements TurboismPlugin {

    private PluginContext context;
    private ScheduledExecutorService scheduler;
    private TextureAtlasEditorPanel panel;
    private String lastText = "";
    /** Guards generation and lastText together so a late poll can never fill a stale cache. */
    private final Object textLock = new Object();

    private int generation;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
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
            final int active;
            synchronized (textLock) {
                active = ++generation;
            }
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
                            update(active, attached, text);
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            update(active, attached, i18n.text("texture-atlas-stats.unavailable"));
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
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        synchronized (textLock) {
            generation++;
            lastText = "";
        }
        if (panel != null) {
            panel.close();
            panel = null;
        }
        enabled = false;
    }

    @Override
    public void shutdown() {
        disable();
        context = null;
    }

    /** Posts to the EDT only for the live enable generation and only on a text change. */
    private void update(final int active, final TextureAtlasEditorPanel attached, final String text) {
        synchronized (textLock) {
            if (active != generation || text.equals(lastText)) {
                return;
            }
            lastText = text;
        }
        SwingUtilities.invokeLater(() -> attached.setText(text));
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas Statistics plugin must be initialized before enable.");
        }
    }
}
