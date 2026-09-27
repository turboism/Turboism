package dev.turboism.sdk.ui.resource;

import java.util.Objects;

/**
 * Immutable raster pixels for a plugin-provided UI image, independent of any host toolkit.
 *
 * @param width positive pixel width
 * @param height positive pixel height
 * @param argb row-major sRGB pixels in non-premultiplied {@code 0xAARRGGBB} format
 */
public record UiRasterImage(int width, int height, int[] argb) {
    public UiRasterImage {
        Objects.requireNonNull(argb, "argb");
        if (width <= 0 || height <= 0 || (long) width * height != argb.length) {
            throw new IllegalArgumentException("positive dimensions must match the pixel count");
        }
        argb = argb.clone();
    }

    /** Returns a copy of the row-major pixels; changing it cannot affect this image. */
    @Override
    public int[] argb() {
        return argb.clone();
    }
}
