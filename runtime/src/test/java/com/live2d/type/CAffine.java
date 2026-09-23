package com.live2d.type;

import java.awt.geom.AffineTransform;

/** Test-only stand-in for the host's {@code com.live2d.type.CAffine}. */
public class CAffine extends AffineTransform {

    public CAffine() {
        super();
    }

    public CAffine(final AffineTransform other) {
        super(other);
    }

    /** Mirrors the host API: the six matrix components as floats. */
    public float[] getMatrix() {
        final double[] m = new double[6];
        getMatrix(m);
        final float[] out = new float[6];
        for (int i = 0; i < 6; i++) out[i] = (float) m[i];
        return out;
    }
}
