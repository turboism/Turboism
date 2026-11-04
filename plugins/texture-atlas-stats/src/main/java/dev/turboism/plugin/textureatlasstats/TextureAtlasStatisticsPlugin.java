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
 *
 * <p>The one-second tick is only a gate: while the contributed panel is not on screen
 * (editor view closed, minimized, or being rebuilt) it reads nothing from the host and
 * dispatches nothing to the UI thread. A visible panel is refreshed only when the
 * rendered text actually changed, so stable counts cost no host repaints either.</p>
 */
public final class TextureAtlasStatisticsPlugin implements TurboismPlugin {

    private static final long POLL_SECONDS = 1L;
    private static final String LINE_KEY = "texture-atlas-stats.line";
    private static final String UNAVAILABLE_KEY = "texture-atlas-stats.unavailable";

    private PluginContext context;
    private UiScheduler uiScheduler;
    private ScheduledExecutorService scheduler;
    private TextureAtlasEditorSession session;
    private PluginLocalization localization;
    private TextureAtlasEditorPanel panel;
    private volatile String publishedText;
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
            final TextureAtlasEditorUi editorUi = context.cubism().textureAtlasEditorUi();
            final TextureAtlasEditorPanel attached = editorUi.attach();
            panel = attached;
            session = context.cubism().textureAtlasEditorSession();
            localization = context.localization();
            publishedText = localization.text(LINE_KEY);
            attached.setText(publishedText);
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "turboism-texture-atlas-stats");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::refresh, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
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
        session = null;
        localization = null;
        publishedText = null;
    }

    @Override
    public void shutdown() {
        disable();
        uiScheduler = null;
        context = null;
    }

    /**
     * One poll tick. The panel handle owns the visibility flag, so an off-screen panel
     * costs nothing beyond this local check: no host session read, no UI-thread hop.
     * Package-private so the lifecycle tests can drive one tick deterministically.
     */
    void refresh() {
        final TextureAtlasEditorPanel target = panel;
        final TextureAtlasEditorSession activeSession = session;
        final PluginLocalization i18n = localization;
        if (!enabled || target == null || activeSession == null || i18n == null) return;
        if (!target.isVisible()) return;
        try {
            final int whole =
                    activeSession.summary().map(TextureAtlasSummary::imageCount).orElse(0);
            final int selected = activeSession
                    .selectedTexture()
                    .map(TextureAtlasSummary::imageCount)
                    .orElse(0);
            publish(target, i18n.format(LINE_KEY, whole, selected));
        } catch (RuntimeException failure) {
            publish(target, i18n.text(UNAVAILABLE_KEY));
        }
    }

    /** Dispatches a refresh only when the rendered text changed. Callers stay on the poll thread. */
    private void publish(final TextureAtlasEditorPanel target, final String text) {
        if (text.equals(publishedText)) return;
        publishedText = text;
        dispatchTextToUi(target, text);
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
