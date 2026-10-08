package com.live2d.cubism.doc.model.deformer.warp;

import java.util.ArrayList;
import java.util.List;

/**
 * Test stub of the reviewed host bezier grid engine
 * ({@code com.live2d.cubism.doc.model.deformer.warp.k}). The bridge resolves the
 * singleton via the static field {@code a} and replays the anchor move
 * {@code a/b(FFZ,points,pt,II)V} plus the directional smoothing passes
 * {@code a/b(points,II,transform,II)V}; only names, arity, and parameter shapes
 * are contract-relevant.
 */
public final class k {

    public static final k a = new k();

    private k() {}

    /** Recorded anchor moves. */
    public static final List<String> calls = new ArrayList<>();

    public void a(
            final float x,
            final float y,
            final boolean flag,
            final Object[][] points,
            final Object pt,
            final int col,
            final int row) {
        calls.add("move.a " + col + "," + row + " -> " + x + "," + y + " flag=" + flag + " pt="
                + System.identityHashCode(pt));
    }

    public void b(
            final float x,
            final float y,
            final boolean flag,
            final Object[][] points,
            final Object pt,
            final int col,
            final int row) {
        calls.add("move.b " + col + "," + row + " -> " + x + "," + y + " flag=" + flag + " pt="
                + System.identityHashCode(pt));
    }

    public void a(
            final Object[][] points,
            final int col,
            final int row,
            final Object transform,
            final int dimension,
            final int smoothLevel) {
        calls.add("smooth.a " + col + "," + row + " dim=" + dimension + " smooth=" + smoothLevel);
    }

    public void b(
            final Object[][] points,
            final int col,
            final int row,
            final Object transform,
            final int dimension,
            final int smoothLevel) {
        calls.add("smooth.b " + col + "," + row + " dim=" + dimension + " smooth=" + smoothLevel);
    }
}
