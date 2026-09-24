/*
 * Ported from whitegreen/Dalsoo-Bin-Packing (https://github.com/whitegreen/Dalsoo-Bin-Packing),
 * MIT License, Copyright (c) 2018 Hao Hua. See LICENSE and NOTICE in this module.
 */
package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.ArrayList;

/**
 * One polygon inside the packing kernel: the raw ring ({@code inpts}, used for
 * scoring and fixed-poly intersection tests) and the spacing-buffered ring
 * ({@code outpts}, used for placement candidates).
 */
final class PackedPoly implements Comparable<PackedPoly> {

    final double[][] source; // untouched source ring
    double[][] outpts;
    final double[][] inpts;
    final double inarea;
    double[] trigo; // [cos, sin] once placed
    double[] position;
    final int id;

    double[] bb; // bounding box of placed inpts
    double[] outBb; // bounding box of placed outpts
    double[] centroid;
    double[] outCentroid;
    int lastQuery; // grid-query dedupe stamp, managed by Bin

    PackedPoly(final int id, final double[][] source, final double[][] buffered,
        final Double segmentMaxLength) {
        this.id = id;
        this.source = source;
        // force a consistent (positive-area) winding, as upstream does
        if (Geom.signedArea(source) > 0) {
            inpts = clone(source);
        } else {
            inpts = new double[source.length][];
            for (int i = 0; i < source.length; i++) {
                inpts[i] = source[source.length - 1 - i].clone();
            }
        }
        inarea = Geom.area(inpts);
        double[][] out = buffered;
        if (segmentMaxLength != null && segmentMaxLength > 0) {
            final ArrayList<double[]> list = new ArrayList<>();
            for (int i = 0; i < out.length; i++) {
                final double[] pa = out[i];
                final double[] pb = out[(i + 1) % out.length];
                list.add(pa);
                final double dis = Geom.distance(pa, pb);
                if (segmentMaxLength < dis) {
                    final int num = 1 + (int) (dis / segmentMaxLength);
                    for (int j = 1; j < num; j++) {
                        final double s = (double) j / num;
                        list.add(new double[] {
                            pa[0] + s * (pb[0] - pa[0]),
                            pa[1] + s * (pb[1] - pa[1])
                        });
                    }
                }
            }
            out = list.toArray(new double[0][]);
        }
        outpts = out;
    }

    private static double[][] clone(final double[][] ps) {
        final double[][] out = new double[ps.length][];
        for (int i = 0; i < ps.length; i++) {
            out[i] = ps[i].clone();
        }
        return out;
    }

    /** Finalizes this polygon's placement and rewrites inpts/outpts into bin space. */
    void fixRotateMove(final double[] cosSin, final double[] dv) {
        trigo = cosSin.clone();
        position = dv.clone();
        transformInPlace(inpts, trigo, dv);
        transformInPlace(outpts, trigo, dv);
    }

    private static void transformInPlace(final double[][] ps, final double[] cosSin, final double[] dv) {
        final double cos = cosSin[0];
        final double sin = cosSin[1];
        for (int i = 0; i < ps.length; i++) {
            final double[] p = ps[i];
            ps[i] = new double[] {
                dv[0] + cos * p[0] - sin * p[1],
                dv[1] + sin * p[0] + cos * p[1]
            };
        }
    }

    /** Pre-computes the values future overlap tests need; call once when placed. */
    void place() {
        bb = Geom.boundingBox(inpts);
        outBb = Geom.boundingBox(outpts);
        centroid = Geom.centroid(inpts);
        outCentroid = Geom.centroid(outpts);
    }

    @Override
    public int compareTo(final PackedPoly other) {
        return Double.compare(this.inarea, other.inarea);
    }
}
