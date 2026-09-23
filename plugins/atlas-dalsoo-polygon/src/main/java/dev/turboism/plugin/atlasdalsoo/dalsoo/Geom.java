/*
 * Ported from whitegreen/Dalsoo-Bin-Packing (https://github.com/whitegreen/Dalsoo-Bin-Packing),
 * MIT License, Copyright (c) 2018 Hao Hua. See LICENSE and NOTICE in this module.
 * Algorithms adapted from Abeysooriya 2018 and Dalalah 2014 by the upstream project.
 * This port removes the JTS dependency and adapts the code to Turboism's data types;
 * the geometric routines below follow the upstream implementation.
 */
package dev.turboism.plugin.atlasdalsoo.dalsoo;

/** Vector and polygon geometry for the Dalsoo packing kernel. */
final class Geom {

    static final double PRECISION = 1.0E-5;

    private Geom() {
    }

    static double[] add(final double[] a, final double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1]};
    }

    static double[] sub(final double[] a, final double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1]};
    }

    static double[] mul(final double[] a, final double s) {
        return new double[] {a[0] * s, a[1] * s};
    }

    static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    static double cross2d(final double[] a, final double[] b) {
        return a[0] * b[1] - a[1] * b[0];
    }

    static double[] mid(final double[] a, final double[] b) {
        return new double[] {(a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5};
    }

    static double distanceSq(final double[] a, final double[] b) {
        final double dx = a[0] - b[0];
        final double dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    static double distance(final double[] a, final double[] b) {
        return Math.sqrt(distanceSq(a, b));
    }

    static double[] rotate(final double[] p, final double[] cosSin) {
        return new double[] {
            p[0] * cosSin[0] - p[1] * cosSin[1],
            p[0] * cosSin[1] + p[1] * cosSin[0]
        };
    }

    /** Rotates every point of {@code ps} by {@code cosSin}. */
    static double[][] rotate(final double[][] ps, final double[] cosSin) {
        final double[][] out = new double[ps.length][];
        for (int i = 0; i < ps.length; i++) {
            out[i] = rotate(ps[i], cosSin);
        }
        return out;
    }

    /** Cosine/sine pair of the given angle in radians. */
    static double[] cosSin(final double radians) {
        return new double[] {Math.cos(radians), Math.sin(radians)};
    }

    /** Signed area of a ring (positive when vertices wind counter-clockwise). */
    static double signedArea(final double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i];
            final double[] b = ring[(i + 1) % ring.length];
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum * 0.5;
    }

    static double area(final double[][] ring) {
        return Math.abs(signedArea(ring));
    }

    /**
     * Strict point-in-polygon test; points on edges or vertices count as inside.
     * Port of upstream {@code insidePolygon}.
     */
    static boolean insidePolygon(final double[] p, final double[][] poly) {
        final int len = poly.length;
        double[][] shifted = new double[len][];
        for (int i = 0; i < len; i++) {
            shifted[i] = new double[] {poly[i][0] - p[0], poly[i][1] - p[1]};
        }
        double[][] trans = new double[len + 1][];
        int num = 0;
        for (int i = 0; i < len; i++) {
            final double[] a = shifted[i];
            final double[] b = shifted[(i + 1) % len];
            if (a[0] * b[1] - a[1] * b[0] == 0 && a[0] * b[0] + a[1] * b[1] <= 0) {
                return true; // on an edge or vertex
            }
            if ((a[1] < 0 && b[1] >= 0) || (a[1] >= 0 && b[1] < 0)) {
                final double x = a[0] - a[1] * (b[0] - a[0]) / (b[1] - a[1]);
                trans[num++] = new double[] {x, 0};
            }
        }
        if (num < 2) {
            return false;
        }
        boolean inside = true;
        for (int i = 0; i < num - 1; i++) {
            for (int j = i + 1; j < num; j++) {
                if (trans[i][0] * trans[j][0] < 0) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * Checks whether two polygons overlap (one vertex inside the other, or any pair
     * of edges intersecting). Port of upstream {@code overlap} / {@code test2BB}.
     */
    static boolean overlap(final double[][] pa, final double[][] pb) {
        for (final double[] p : pa) {
            if (insidePolygon(p, pb)) {
                return true;
            }
        }
        for (final double[] p : pb) {
            if (insidePolygon(p, pa)) {
                return true;
            }
        }
        for (int i = 0; i < pa.length; i++) {
            final double[] a0 = pa[i];
            final double[] a1 = pa[(i + 1) % pa.length];
            for (int j = 0; j < pb.length; j++) {
                final double[] b0 = pb[j];
                final double[] b1 = pb[(j + 1) % pb.length];
                if (segmentsIntersect(a0, a1, b0, b1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strict point-in-polygon ray cast (port of upstream {@code inside}); points on
     * the boundary are not reliably classified.
     */
    static boolean insideStrict(final double[] p, final double[][] vs) {
        boolean odd = false;
        int j = vs.length - 1;
        for (int i = 0; i < vs.length; i++) {
            final double[] vi = vs[i];
            final double[] vj = vs[j];
            if ((vi[1] < p[1] && vj[1] >= p[1] || vj[1] < p[1] && vi[1] >= p[1])
                && (vi[0] <= p[0] || vj[0] <= p[0])) {
                if (vi[0] + (p[1] - vi[1]) / (vj[1] - vi[1]) * (vj[0] - vi[0]) < p[0]) {
                    odd = !odd;
                }
            }
            j = i;
        }
        return odd;
    }

    /** Twice the signed triangle area (port of upstream {@code triangleArea}). */
    static double triangleArea(final double[] a, final double[] b, final double[] c) {
        return a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]);
    }

    /**
     * Strict segment crossing (port of upstream {@code intersectionFast}): shared
     * endpoints and collinear contact do not count.
     */
    static boolean segmentsCrossStrict(final double[] a, final double[] b,
        final double[] c, final double[] d) {
        return triangleArea(a, b, d) * triangleArea(a, b, c) < 0
            && triangleArea(c, d, a) * triangleArea(c, d, b) < 0;
    }

    /**
     * Strict overlap for placement feasibility (port of upstream
     * {@code overlapFast}, extended symmetrically): bounding-box early exit, then
     * either centroid inside the other polygon, then strict edge crossings.
     */
    static boolean overlapStrict(final double[][] candidate, final double[] candidateBb,
        final double[] candidateCentroid, final double[][] placed,
        final double[] placedBb, final double[] placedCentroid) {
        if (candidateBb[0] > placedBb[2] || placedBb[0] > candidateBb[2]
            || candidateBb[1] > placedBb[3] || placedBb[1] > candidateBb[3]) {
            return false;
        }
        if (insideStrict(placedCentroid, candidate) || insideStrict(candidateCentroid, placed)) {
            return true;
        }
        // containment without edge crossings: a concave polygon's centroid can
        // lie outside itself, so vertices must be probed as well (upstream
        // semantics)
        for (final double[] p : candidate) {
            if (insideStrict(p, placed)) {
                return true;
            }
        }
        for (final double[] p : placed) {
            if (insideStrict(p, candidate)) {
                return true;
            }
        }
        for (int i = 0; i < candidate.length; i++) {
            final double[] a0 = candidate[i];
            final double[] a1 = candidate[(i + 1) % candidate.length];
            for (int j = 0; j < placed.length; j++) {
                final double[] b0 = placed[j];
                final double[] b1 = placed[(j + 1) % placed.length];
                if (segmentsCrossStrict(a0, a1, b0, b1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fast overlap test given both bounding boxes first.
     * Port of upstream {@code overlapFast}: bounding-box early exit then {@link #overlap}.
     */
    static boolean overlapFast(final double[][] pa, final double[][] pb,
        final double[] bba, final double[] bbb) {
        if (bba[0] > bbb[2] || bbb[0] > bba[2] || bba[1] > bbb[3] || bbb[1] > bba[3]) {
            return false;
        }
        return overlap(pa, pb);
    }

    /** Bounding box {@code [minX, minY, maxX, maxY]} of a ring. */
    static double[] boundingBox(final double[][] ring) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (final double[] p : ring) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /**
     * Whether two segments {@code a0-a1} and {@code b0-b1} intersect, including
     * endpoint contacts. Port of upstream {@code intersect2lines}.
     */
    static boolean segmentsIntersect(final double[] a0, final double[] a1,
        final double[] b0, final double[] b1) {
        final double d1 = cross2d(sub(b0, a0), sub(a1, a0)) * cross2d(sub(b1, a0), sub(a1, a0));
        final double d2 = cross2d(sub(a0, b0), sub(b1, b0)) * cross2d(sub(a1, b0), sub(b1, b0));
        return d1 <= 0 && d2 <= 0;
    }

    /**
     * Returns the factor {@code t} at which segment {@code p->v} crosses the edge
     * {@code v0->v1} ({@code p + t*v}), or {@code -1} when parallel or outside
     * {@code [0,1]}. Port of upstream {@code factorCross2Lines}.
     */
    static double factorCross2Lines(final double[] p, final double[] v,
        final double[] v0, final double[] v1) {
        final double[] w = sub(v1, v0);
        final double denominator = cross2d(v, w);
        if (denominator == 0) {
            return -1;
        }
        final double[] q = sub(v0, p);
        final double t = cross2d(q, w) / denominator;
        if (t < 0 || t > 1) {
            return -1;
        }
        return cross2d(q, v) / denominator;
    }

    /**
     * Ray-casts the polygon boundary along direction {@code v} starting at vertex
     * {@code index} of {@code inps}; returns the edge index whose factor is
     * positive-maximal, or {@code -1}. Port of upstream {@code edgeCrossPolygon}.
     */
    static int edgeCrossPolygon(final double[][] inps, final double[][] ps,
        final double[] v, final int index) {
        int j = -1;
        double fmax = Double.MAX_VALUE;
        final double[] p = inps[index];
        for (int i = 0; i < ps.length; i++) {
            final double f = factorCross2Lines(p, v, ps[i], ps[(i + 1) % ps.length]);
            if (f >= 0 && f < fmax) {
                j = i;
                fmax = f;
            }
        }
        return j;
    }

    /**
     * Distance from {@code p} to line {@code a-b}, or {@code Double.MAX_VALUE} when
     * the projection falls outside the segment. Port of upstream {@code distanceToSeg}.
     */
    static double distanceToSeg(final double[] a, final double[] b, final double[] p) {
        final double[] ab = sub(b, a);
        final double t = (ab[0] * (p[0] - a[0]) + ab[1] * (p[1] - a[1]))
            / (ab[0] * ab[0] + ab[1] * ab[1]);
        if (t < 0 || t > 1) {
            return Double.MAX_VALUE;
        }
        final double[] foot = new double[] {a[0] + t * ab[0], a[1] + t * ab[1]};
        return distance(p, foot);
    }

    /** Whether {@code p} lies within {@link #PRECISION} of segment {@code a-b}. */
    static boolean onSegment(final double[] a, final double[] b, final double[] p) {
        final double[] ab = sub(b, a);
        final double lenSq = ab[0] * ab[0] + ab[1] * ab[1];
        if (lenSq < PRECISION * PRECISION) {
            return distanceSq(a, p) < PRECISION * PRECISION;
        }
        double t = (ab[0] * (p[0] - a[0]) + ab[1] * (p[1] - a[1])) / lenSq;
        t = Math.max(0, Math.min(1, t));
        final double dx = a[0] + t * ab[0] - p[0];
        final double dy = a[1] + t * ab[1] - p[1];
        return dx * dx + dy * dy < PRECISION * PRECISION;
    }

    static double[] centroid(final double[][] ring) {
        double sx = 0, sy = 0;
        for (final double[] p : ring) {
            sx += p[0];
            sy += p[1];
        }
        return new double[] {sx / ring.length, sy / ring.length};
    }
}
