package dev.turboism.plugin.artmeshmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.ui.resource.UiRasterImage;
import org.junit.jupiter.api.Test;

final class ArtMeshMirrorPluginTest {
    private static final int EXPECTED_WIDTH = 120;
    private static final int EXPECTED_HEIGHT = 72;
    private static final int EXPECTED_KEYLINE_RGB = 0xFFF4F3FF;
    private static final int EXPECTED_PURPLE_RGB = 0xFF6752C7;
    private static final int EXPECTED_WHITE_RGB = 0xFFFFFFFF;
    private static final int KEYLINE_PROBE_X = 83;
    private static final int KEYLINE_PROBE_Y = 35;
    private static final int PURPLE_PROBE_X = 90;
    private static final int PURPLE_PROBE_Y = 59;
    private static final int T_CENTER_X = 99;
    private static final int T_CENTER_Y = 51;

    @Test
    void bothStatesIncludeBottomRightTurboismBadge() {
        for (boolean enabled : new boolean[] {false, true}) {
            UiRasterImage icon = ArtMeshMirrorPlugin.icon(enabled);
            assertEquals(EXPECTED_WIDTH, icon.width());
            assertEquals(EXPECTED_HEIGHT, icon.height());
            assertEquals(EXPECTED_KEYLINE_RGB, pixel(icon, KEYLINE_PROBE_X, KEYLINE_PROBE_Y));
            assertEquals(EXPECTED_PURPLE_RGB, pixel(icon, PURPLE_PROBE_X, PURPLE_PROBE_Y));
            assertEquals(EXPECTED_WHITE_RGB, pixel(icon, T_CENTER_X, T_CENTER_Y));
            assertTrue(pixel(icon, 0, 0) == 0, "background outside glyph and badge must stay transparent");
        }
    }

    private static int pixel(UiRasterImage image, int x, int y) {
        return image.argb()[y * image.width() + x];
    }
}
