package dev.turboism.sdk.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe focused tests for the plugin-owned window factory Preview
 * API. No AWT window is ever constructed here; the headless guard test is
 * skipped on JVMs that are not headless.
 */
class TurboismWindowFactoryTest {

    private static final String ICON_RESOURCE =
        "dev/turboism/sdk/ui/window/turboism-window-icon.png";

    @Test
    void windowIconLoadsFromSdkClasspath() {
        Image icon = TurboismWindowFactory.windowIcon();

        assertNotNull(icon, "window icon resource must be present and decodable");
        assertTrue(icon.getWidth(null) > 0, "icon width must be positive");
        assertTrue(icon.getHeight(null) > 0, "icon height must be positive");
    }

    @Test
    void windowIconResourceIsAValidPng() throws Exception {
        try (java.io.InputStream in = TurboismWindowFactory.class
            .getClassLoader()
            .getResourceAsStream(ICON_RESOURCE)) {
            assertNotNull(in, "icon resource must be on the SDK classpath");

            BufferedImage decoded = ImageIO.read(in);
            assertNotNull(decoded, "icon bytes must decode as a PNG");
            assertEquals(256, decoded.getWidth(), "product title icon width");
            assertEquals(256, decoded.getHeight(), "product title icon height");
        }
    }

    @Test
    void styleNullIsNoOp() {
        TurboismWindowFactory.style(null);
    }

    @Test
    void missingIconResourceDegradesGracefullyToNull() {
        assertNull(TurboismWindowFactory.loadWindowIcon(
            "dev/turboism/sdk/ui/window/definitely-missing-window-icon.png"));
        assertNull(TurboismWindowFactory.loadWindowIcon(null));
    }

    @Test
    void headlessFactoryFailsClosedWithoutConstructingWindows() {
        assumeTrue(GraphicsEnvironment.isHeadless(), "requires a headless JVM");

        assertNull(TurboismWindowFactory.dialog(null, "title", false));
        assertNull(TurboismWindowFactory.frame("title"));
    }
}
