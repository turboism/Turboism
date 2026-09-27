/*
 * Ported from whitegreen/Dalsoo-Bin-Packing (https://github.com/whitegreen/Dalsoo-Bin-Packing),
 * MIT License, Copyright (c) 2018 Hao Hua. See LICENSE and NOTICE in this module.
 */
package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.ArrayList;
import java.util.List;

/** Incrementally maintained convex hull used to score placements. */
final class Convex implements Cloneable {

    private static final double ZERO_AREA = 1E-6;

    List<double[]> convex; // vertices wound clockwise

    private Convex() {
    }

    Convex(final PackedPoly strip) {
        this(strip.inpts);
    }

    Convex(final double[][] points) {
        final double area = Geom.signedArea(points);
        if (Math.abs(area) < ZERO_AREA) {
            throw new IllegalArgumentException("degenerate polygon");
        }
        convex = new ArrayList<>();
        if (area > 0) {
            convex.add(points[0]);
            convex.add(points[1]);
            convex.add(points[2]);
        } else {
            convex.add(points[1]);
            convex.add(points[0]);
            convex.add(points[2]);
        }
        for (int i = 3; i < points.length; i++) {
            incrementHull(points[i]);
        }
    }

    @Override
    public Convex clone() {
        final Convex copy = new Convex();
        copy.convex = new ArrayList<>(convex);
        return copy;
    }

    double[][] vertices() {
        return convex.toArray(new double[0][]);
    }

    void incrementHull(final double[] np) {
        if (Geom.insidePolygon(np, convex.toArray(new double[0][]))) {
            return;
        }
        final int size = convex.size();
        final boolean[] visible = new boolean[size];
        boolean any = false;
        for (int i = 0; i < size; i++) {
            final double[] q0 = convex.get(i);
            final double[] q1 = convex.get((i + 1) % size);
            final double[] d = Geom.sub(q1, q0);
            final double[] a = Geom.sub(np, q0);
            visible[i] = -d[1] * a[0] + d[0] * a[1] > 0;
            if (visible[i]) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        int first = -1;
        for (int i = 0; i < size; i++) {
            final int j = (i + 1) % size;
            if (!visible[i] && visible[j]) {
                first = j;
                break;
            }
        }
        if (first < 0) {
            return; // degenerate visibility set; keep hull unchanged
        }
        final List<double[]> next = new ArrayList<>(size + 1);
        next.add(convex.get(first));
        next.add(np);
        for (int i = 0; i < size; i++) {
            final int j = (first + i + 1) % size;
            if (!visible[j]) {
                next.add(convex.get(j));
            }
        }
        convex = next;
    }
}
