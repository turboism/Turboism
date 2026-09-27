package com.live2d.graphics;

import java.awt.image.BufferedImage;

/** Test-only stand-in for the host's {@code com.live2d.graphics.CWritableImage}. */
public class CWritableImage {
    private BufferedImage image;

    public CWritableImage(final int width, final int height) {
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    public CWritableImage(final BufferedImage image) {
        this.image = image;
    }

    public final BufferedImage getJBufferedImage() {
        return image;
    }

    public final void setJBufferedImage(final BufferedImage image) {
        this.image = image;
    }

    /** Mirrors the host's deep copy: a new image, duplicated pixels. */
    public final CWritableImage copyAs(final n colorType, final boolean force) {
        final BufferedImage copy = new BufferedImage(
            image.getWidth(), image.getHeight(), image.getType());
        copy.setData(image.getData());
        return new CWritableImage(copy);
    }
}
