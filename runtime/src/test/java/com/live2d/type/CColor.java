package com.live2d.type;

/** Test-only Cubism color value used by the off-canvas reflection fixture. */
public final class CColor {
    private final int red;
    private final int green;
    private final int blue;

    public CColor(final int red, final int green, final int blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }
}
