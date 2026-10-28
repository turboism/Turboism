package dev.turboism.ui.toolbar;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;

/** Loads plugin toolbar icons with dark-mode preference and optional Windows-style DPI variants. */
public final class ToolbarIconLoader {

    private static final String[] SCALE_SUFFIXES = {"125", "150", "175", "200"};

    private ToolbarIconLoader() {}

    public static Icon load(
            final EditorUiPluginResourceRegistry resources, final String pluginId, final String resourcePath) {
        return load(resources, pluginId, 0L, resourcePath, isDarkTheme());
    }

    public static Icon load(
            final EditorUiPluginResourceRegistry resources,
            final String pluginId,
            final long pluginGeneration,
            final String resourcePath) {
        return load(resources, pluginId, pluginGeneration, resourcePath, isDarkTheme());
    }

    public static Icon load(
            final EditorUiPluginResourceRegistry resources,
            final String pluginId,
            final long pluginGeneration,
            final String resourcePath,
            final boolean dark) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(resourcePath, "resourcePath");

        final String effectivePath = resolveEffectivePath(resources, pluginId, pluginGeneration, resourcePath, dark);
        final URL base = resources
                .resource(pluginId, pluginGeneration, effectivePath)
                .orElseThrow(() -> new IllegalStateException("toolbar icon resource is unavailable: " + effectivePath));

        final List<Image> variants = new ArrayList<>();
        variants.add(read(base, effectivePath));
        for (final String suffix : SCALE_SUFFIXES) {
            resources
                    .resource(pluginId, pluginGeneration, scaleVariantPath(effectivePath, suffix))
                    .map(url -> read(url, effectivePath))
                    .ifPresent(variants::add);
        }
        if (variants.size() == 1) {
            return new ImageIcon(variants.get(0));
        }
        return new ImageIcon(new BaseMultiResolutionImage(variants.toArray(Image[]::new)));
    }

    private static String resolveEffectivePath(
            final EditorUiPluginResourceRegistry resources,
            final String pluginId,
            final long pluginGeneration,
            final String resourcePath,
            final boolean dark) {
        if (!dark) {
            return resourcePath;
        }
        final String darkPath = darkVariantPath(resourcePath);
        if (resources.resource(pluginId, pluginGeneration, darkPath).isPresent()) {
            return darkPath;
        }
        return resourcePath;
    }

    public static boolean isDarkTheme() {
        final LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        if (lookAndFeel != null) {
            final String signature =
                    (lookAndFeel.getName() + " " + lookAndFeel.getClass().getName()).toLowerCase(Locale.ROOT);
            if (signature.contains("dark")) {
                return true;
            }
        }
        final Color background = UIManager.getColor("Panel.background");
        if (background != null) {
            final int luma =
                    (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114) / 1000;
            return luma < 140;
        }
        return false;
    }

    public static String darkVariantPath(final String resourcePath) {
        final int extension = resourcePath.lastIndexOf('.');
        if (extension <= resourcePath.lastIndexOf('/')) {
            throw new IllegalArgumentException("toolbar icon resource must have an extension");
        }
        final String base = resourcePath.substring(0, extension);
        if (base.endsWith("-dark")) {
            return resourcePath;
        }
        return base + "-dark" + resourcePath.substring(extension);
    }

    public static String scaleVariantPath(final String resourcePath, final String suffix) {
        final int extension = resourcePath.lastIndexOf('.');
        if (extension <= resourcePath.lastIndexOf('/')) {
            throw new IllegalArgumentException("toolbar icon resource must have an extension");
        }
        return resourcePath.substring(0, extension) + ".scale-" + suffix + resourcePath.substring(extension);
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
                    "toolbar icon resource is not a readable raster image: " + resourcePath, failure);
        }
    }
}
