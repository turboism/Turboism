package dev.turboism.plugin.textureatlasstats;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/**
 * Contributes a single-line model-image counter into the native texture-atlas editor
 * window through the framework UI/session capabilities:
 * {@code Total model images: 849, current texture model images: 37}.
 */
public final class TextureAtlasStatisticsPlugin implements TurboismPlugin {

    private PluginContext context;
    private javax.swing.Timer timer;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Texture Atlas Statistics plugin initialized");
    }

    @Override
    public void enable() {
        requireContext();
        enabled = true;
        try {
            final TextureAtlasEditorSession session = context.cubism().textureAtlasEditorSession();
            final TextureAtlasEditorUi editorUi = context.cubism().textureAtlasEditorUi();
            final PluginLocalization i18n = context.localization();
            final javax.swing.JLabel line = new javax.swing.JLabel(
                i18n.text("texture-atlas-stats.line")
            );
            timer = new javax.swing.Timer(1000, event -> {
                try {
                    final int whole = session.summary()
                        .map(TextureAtlasSummary::imageCount)
                        .orElse(0);
                    final int selected = session.selectedTexture()
                        .map(TextureAtlasSummary::imageCount)
                        .orElse(0);
                    line.setText(i18n.format("texture-atlas-stats.line", whole, selected));
                } catch (Throwable failure) {
                    line.setText(i18n.text("texture-atlas-stats.unavailable"));
                }
            });
            timer.setRepeats(true);
            timer.start();
            editorUi.attach(line);
        } catch (Throwable failure) {
            context.logger().warn("Texture Atlas Statistics panel unavailable: " + failure);
        }
    }

    @Override
    public void disable() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        enabled = false;
    }

    @Override
    public void shutdown() {
        disable();
        context = null;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas Statistics plugin must be initialized before enable.");
        }
    }
}
