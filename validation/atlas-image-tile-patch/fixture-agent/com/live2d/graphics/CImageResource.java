package com.live2d.graphics;
/** Fixture: resource wrapper with getImage(). */
public class CImageResource {
    private final CWritableImage img;
    public CImageResource(CWritableImage i) { img = i; }
    public CWritableImage getImage() { return img; }
}
