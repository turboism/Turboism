package dev.turboism.sdk.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless-safe focused tests for the plugin-owned window factory Preview
 * API. No AWT window is ever constructed here; the headless guard test is
 * skipped on JVMs that are not headless.
 */
class TurboismWindowFactoryTest {

    private static final String ICON_RESOURCE = "dev/turboism/sdk/ui/window/turboism-window-icon.png";

    @Test
    void windowIconLoadsWhenFactoryIsOnBootClasspath(@TempDir final Path directory) throws Exception {
        final Path jar = directory.resolve("window-factory.jar");
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (String resource :
                    new String[] {"dev/turboism/sdk/ui/window/TurboismWindowFactory.class", ICON_RESOURCE}) {
                out.putNextEntry(new JarEntry(resource));
                try (java.io.InputStream in = TurboismWindowFactory.class.getResourceAsStream("/" + resource)) {
                    assertNotNull(in, resource);
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        final Path log = directory.resolve("bootstrap-icon.log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", executable)
                                .toString(),
                        "-Djava.awt.headless=true",
                        "-Xbootclasspath/a:" + jar,
                        "-cp",
                        Path.of(BootstrapIconProbe.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                                .toString(),
                        BootstrapIconProbe.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bootstrap icon probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return java.nio.file.Files.readString(log);
                } catch (java.io.IOException failure) {
                    return failure.toString();
                }
            });
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class BootstrapIconProbe {
        public static void main(final String[] args) {
            if (TurboismWindowFactory.class.getClassLoader() != null) {
                throw new AssertionError("Factory must use the bootstrap loader, as in the packaged agent");
            }
            if (TurboismWindowFactory.windowIcon() == null) {
                throw new AssertionError("Bundled window icon is missing under Boot-Class-Path");
            }
        }
    }

    @Test
    void windowIconLoadsFromSdkClasspath() {
        Image icon = TurboismWindowFactory.windowIcon();

        assertNotNull(icon, "window icon resource must be present and decodable");
        assertTrue(icon.getWidth(null) > 0, "icon width must be positive");
        assertTrue(icon.getHeight(null) > 0, "icon height must be positive");
    }

    @Test
    void windowIconResourceIsAValidPng() throws Exception {
        try (java.io.InputStream in =
                TurboismWindowFactory.class.getClassLoader().getResourceAsStream(ICON_RESOURCE)) {
            assertNotNull(in, "icon resource must be on the SDK classpath");

            BufferedImage decoded = ImageIO.read(in);
            assertNotNull(decoded, "icon bytes must decode as a PNG");
            assertEquals(256, decoded.getWidth(), "product title icon width");
            assertEquals(256, decoded.getHeight(), "product title icon height");
        }
    }

    @Test
    void installedWindowIconOverridesBundledDefault() {
        final BufferedImage override = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        try {
            TurboismWindowFactory.installWindowIcon(override);
            assertSame(override, TurboismWindowFactory.windowIcon());
        } finally {
            TurboismWindowFactory.installWindowIcon(null);
        }
        assertNotSame(override, TurboismWindowFactory.windowIcon());
    }

    @Test
    void styleNullIsNoOp() {
        TurboismWindowFactory.style(null);
    }

    @Test
    void missingIconResourceDegradesGracefullyToNull() {
        assertNull(
                TurboismWindowFactory.loadWindowIcon("dev/turboism/sdk/ui/window/definitely-missing-window-icon.png"));
        assertNull(TurboismWindowFactory.loadWindowIcon(null));
    }

    @Test
    void headlessFactoryFailsClosedWithoutConstructingWindows() {
        assumeTrue(GraphicsEnvironment.isHeadless(), "requires a headless JVM");

        assertNull(TurboismWindowFactory.dialog(null, "title", false));
        assertNull(TurboismWindowFactory.frame("title"));
    }
}
