package com.live2d.graphics;

/** Test-only stand-in for the host's {@code com.live2d.graphics.CImageResource}. */
public class CImageResource {
    private static final n DEFAULT_COLOR_TYPE = n.ARGB;

    private final CWritableImage image;

    public CImageResource(final CWritableImage image) {
        this.image = image;
    }

    public CImageResource(final CWritableImage image, final n colorType,
                          final boolean mipmapped) {
        this.image = image;
    }

    public final CWritableImage getImage() {
        return image;
    }

    public final int getWidth() {
        return image.getJBufferedImage().getWidth();
    }

    public final int getHeight() {
        return image.getJBufferedImage().getHeight();
    }
}
