package dev.turboism.plugin.frameanimation.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class FrameAnimationDialogLayoutTest {

    private static final PluginLocalization FAKE_LOC = new PluginLocalization() {
        private final ResourceBundle bundle = ResourceBundle.getBundle("META-INF.turboism.i18n.messages", Locale.ROOT);

        @Override
        public Locale locale() {
            return Locale.ROOT;
        }

        @Override
        public String text(final String key) {
            return bundle.containsKey(key) ? bundle.getString(key) : key;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return java.text.MessageFormat.format(text(key), arguments);
        }

        @Override
        public boolean contains(final String key) {
            return bundle.containsKey(key);
        }
    };

    @Test
    void previewPanelRendersWithoutException() {
        final SpriteSheetPreviewPanel panel = new SpriteSheetPreviewPanel(FAKE_LOC);
        final BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        panel.update(img, SpriteSheetConfig.of(2, 2));
        panel.setSize(300, 200);

        final BufferedImage renderTarget = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        panel.paint(renderTarget.createGraphics());
        assertNotNull(panel);
    }

    @Test
    void dialogInstantiatesInNonHeadlessEnvironment() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        final FrameAnimationDialog dialog = new FrameAnimationDialog(null, FAKE_LOC);
        assertNotNull(dialog);
        assertTrue(dialog.getTitle().contains("Animation") || dialog.getTitle().contains("动画"));
        dialog.dispose();
    }
}
