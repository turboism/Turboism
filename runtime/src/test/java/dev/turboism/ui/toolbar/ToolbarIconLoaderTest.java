package dev.turboism.ui.toolbar;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            new URL[] {root.toUri().toURL()},
            getClass().getClassLoader()
        ); EditorUiPluginResourceRegistry resources = new EditorUiPluginResourceRegistry()) {
            try (var registration = resources.register("fixture", loader)) {
                final ImageIcon icon = assertInstanceOf(
                    ImageIcon.class,
                    ToolbarIconLoader.load(resources, "fixture", "icons/action.png")
                );
                final MultiResolutionImage image = assertInstanceOf(
                    MultiResolutionImage.class,
                    icon.getImage()
                );

                assertEquals(
                    List.of(32, 40, 48, 56, 64),
                    image.getResolutionVariants().stream().map(value -> value.getWidth(null)).toList()
                );
            }
        }
    }

    @Test
    void derivesScaleVariantPathsBeforeTheExtension() {
        assertEquals(
            "icons/action.scale-150.png",
            ToolbarIconLoader.scaleVariantPath("icons/action.png", "150")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ToolbarIconLoader.scaleVariantPath("icons/action", "150")
        );
    }

    private static void writePng(final Path root, final String resource, final int size)
        throws IOException {
        final Path path = root.resolve(resource);
        Files.createDirectories(path.getParent());
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", path.toFile());
    }
}
