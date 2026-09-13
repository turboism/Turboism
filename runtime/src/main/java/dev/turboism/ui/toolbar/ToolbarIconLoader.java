package dev.turboism.ui.toolbar;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads plugin toolbar icons with optional Windows-style DPI variants. */
final class ToolbarIconLoader {

    private static final String[] SCALE_SUFFIXES = {"125", "150", "175", "200"};

    private ToolbarIconLoader() {
    }

    static Icon load(
        final EditorUiPluginResourceRegistry resources,
        final String pluginId,
        final String resourcePath
    ) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(resourcePath, "resourcePath");

        final URL base = resources.resource(pluginId, resourcePath)
            .orElseThrow(() -> new IllegalStateException("toolbar icon resource is unavailable"));
        final List<Image> variants = new ArrayList<>();
        variants.add(read(base, resourcePath));
        for (final String suffix : SCALE_SUFFIXES) {
            resources.resource(pluginId, scaleVariantPath(resourcePath, suffix))
                .map(url -> read(url, resourcePath))
                .ifPresent(variants::add);
        }
        if (variants.size() == 1) {
            return new ImageIcon(variants.get(0));
        }
        return new ImageIcon(new BaseMultiResolutionImage(variants.toArray(Image[]::new)));
    }

    static String scaleVariantPath(final String resourcePath, final String suffix) {
        final int extension = resourcePath.lastIndexOf('.');
        if (extension <= resourcePath.lastIndexOf('/')) {
            throw new IllegalArgumentException("toolbar icon resource must have an extension");
        }
        return resourcePath.substring(0, extension)
            + ".scale-" + suffix
            + resourcePath.substring(extension);
    }

    private static BufferedImage read(final URL url, final String resourcePath) {
        try {
            final BufferedImage image = ImageIO.read(url);
            if (image == null) {
                throw new IllegalArgumentException("resource is not a readable raster image");
            }
            return image;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException(
                "toolbar icon resource is not a readable raster image: " + resourcePath,
                failure
            );
        }
    }
}
