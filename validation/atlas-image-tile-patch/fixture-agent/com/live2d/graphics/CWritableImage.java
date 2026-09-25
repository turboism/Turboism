package com.live2d.graphics;
import java.awt.image.BufferedImage;
/** Fixture: writable image with getJBufferedImage(). */
public class CWritableImage {
    private final BufferedImage img;
    public CWritableImage(BufferedImage i) { img = i; }
    public BufferedImage getJBufferedImage() { return img; }
}
