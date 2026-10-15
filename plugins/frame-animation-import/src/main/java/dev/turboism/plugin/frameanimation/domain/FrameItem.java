package dev.turboism.plugin.frameanimation.domain;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * An individual frame in an animation sequence.
 *
 * @param index 0-based frame index
 * @param name display name of this frame (e.g. "Frame_0" or file name)
 * @param image raster image for this frame
 */
public record FrameItem(int index, String name, BufferedImage image) {

    public FrameItem {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative: " + index);
        }
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(image, "image");
    }

    /** Returns the raster width in pixels. */
    public int width() {
        return image.getWidth();
    }

    /** Returns the raster height in pixels. */
    public int height() {
        return image.getHeight();
    }
}
