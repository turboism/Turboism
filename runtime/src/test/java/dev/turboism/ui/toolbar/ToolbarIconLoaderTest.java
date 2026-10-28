package dev.turboism.ui.toolbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

class ToolbarIconLoaderTest {

    @Test
    void loadsAvailableScaleVariantsAsAJavaMultiResolutionImage() throws Exception {
        final Path root = Files.createTempDirectory("toolbar-icon-loader");
        writePng(root, "icons/action.png", 32);
        writePng(root, "icons/action.scale-125.png", 40);
        writePng(root, "icons/action.scale-150.png", 48);
        writePng(root, "icons/action.scale-175.png", 56);
        writePng(root, "icons/action.scale-200.png", 64);

        try (URLClassLoader loader = new URLClassLoader(
                        new URL[] {root.toUri().toURL()}, getClass().getClassLoader());
                EditorUiPluginResourceRegistry resources = new EditorUiPluginResourceRegistry()) {
            try (var registration = resources.register("fixture", loader)) {
                final ImageIcon icon = assertInstanceOf(
                        ImageIcon.class, ToolbarIconLoader.load(resources, "fixture", "icons/action.png"));
                final MultiResolutionImage image = assertInstanceOf(MultiResolutionImage.class, icon.getImage());

                assertEquals(
                        List.of(32, 40, 48, 56, 64),
                        image.getResolutionVariants().stream()
                                .map(value -> value.getWidth(null))
                                .toList());
            }
        }
    }

    @Test
    void derivesScaleVariantPathsBeforeTheExtension() {
        assertEquals("icons/action.scale-150.png", ToolbarIconLoader.scaleVariantPath("icons/action.png", "150"));
        assertThrows(IllegalArgumentException.class, () -> ToolbarIconLoader.scaleVariantPath("icons/action", "150"));
    }

    @Test
    void derivesDarkVariantPathsBeforeTheExtensionAndIsIdempotent() {
        assertEquals("icons/action-dark.png", ToolbarIconLoader.darkVariantPath("icons/action.png"));
        assertEquals("icons/action-dark.png", ToolbarIconLoader.darkVariantPath("icons/action-dark.png"));
        assertThrows(IllegalArgumentException.class, () -> ToolbarIconLoader.darkVariantPath("icons/action"));
    }

    @Test
    void loadsDarkVariantWhenDarkRequestedAndAvailable() throws Exception {
        final Path root = Files.createTempDirectory("toolbar-icon-dark");
        writePng(root, "icons/brush.png", 32);
        writePng(root, "icons/brush-dark.png", 40);

        try (URLClassLoader loader = new URLClassLoader(
                        new URL[] {root.toUri().toURL()}, getClass().getClassLoader());
                EditorUiPluginResourceRegistry resources = new EditorUiPluginResourceRegistry()) {
            try (var registration = resources.register("fixture", 1L, loader)) {
                final ImageIcon lightIcon = assertInstanceOf(
                        ImageIcon.class, ToolbarIconLoader.load(resources, "fixture", 1L, "icons/brush.png", false));
                assertEquals(32, lightIcon.getIconWidth());

                final ImageIcon darkIcon = assertInstanceOf(
                        ImageIcon.class, ToolbarIconLoader.load(resources, "fixture", 1L, "icons/brush.png", true));
                assertEquals(40, darkIcon.getIconWidth());
            }
        }
    }

    @Test
    void fallsBackToNormalWhenDarkRequestedButUnavailable() throws Exception {
        final Path root = Files.createTempDirectory("toolbar-icon-fallback");
        writePng(root, "icons/single.png", 32);

        try (URLClassLoader loader = new URLClassLoader(
                        new URL[] {root.toUri().toURL()}, getClass().getClassLoader());
                EditorUiPluginResourceRegistry resources = new EditorUiPluginResourceRegistry()) {
            try (var registration = resources.register("fixture", 1L, loader)) {
                final ImageIcon darkIcon = assertInstanceOf(
                        ImageIcon.class, ToolbarIconLoader.load(resources, "fixture", 1L, "icons/single.png", true));
                assertEquals(32, darkIcon.getIconWidth());
            }
        }
    }

    @Test
    void loadsDarkScaleVariantsWhenAvailable() throws Exception {
        final Path root = Files.createTempDirectory("toolbar-icon-dark-scale");
        writePng(root, "icons/brush.png", 32);
        writePng(root, "icons/brush-dark.png", 32);
        writePng(root, "icons/brush-dark.scale-150.png", 48);

        try (URLClassLoader loader = new URLClassLoader(
                        new URL[] {root.toUri().toURL()}, getClass().getClassLoader());
                EditorUiPluginResourceRegistry resources = new EditorUiPluginResourceRegistry()) {
            try (var registration = resources.register("fixture", 1L, loader)) {
                final ImageIcon icon = assertInstanceOf(
                        ImageIcon.class, ToolbarIconLoader.load(resources, "fixture", 1L, "icons/brush.png", true));
                final MultiResolutionImage image = assertInstanceOf(MultiResolutionImage.class, icon.getImage());

                assertEquals(
                        List.of(32, 48),
                        image.getResolutionVariants().stream()
                                .map(value -> value.getWidth(null))
                                .toList());
            }
        }
    }

    @Test
    void isDarkThemeDetectsPanelBackgroundLuma() {
        final Object previous = UIManager.get("Panel.background");
        try {
            UIManager.put("Panel.background", new Color(30, 30, 30));
            assertTrue(ToolbarIconLoader.isDarkTheme(), "Luma < 140 must be recognized as dark theme");

            UIManager.put("Panel.background", new Color(240, 240, 240));
            assertFalse(
                    ToolbarIconLoader.isDarkTheme(), "Luma >= 140 under light LAF must be recognized as light theme");
        } finally {
            UIManager.put("Panel.background", previous);
        }
    }

    private static void writePng(final Path root, final String resource, final int size) throws IOException {
        final Path path = root.resolve(resource);
        Files.createDirectories(path.getParent());
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", path.toFile());
    }
}
