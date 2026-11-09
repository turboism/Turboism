package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.List;

/**
 * Even-odd containment and nesting depth of a disjoint ring set.
 *
 * <p>Ring order says nothing about material: a silhouette and an island look alike, and so do a
 * hole and a second part. Both questions therefore follow the pixel rule the triangulator uses — a
 * point is material when an even number of rings surround it, and a ring is a hole only when its
 * nesting depth is odd.</p>
 */
final class RingNesting {

    /** Offset from an edge midpoint to the ring interior, in pixels. */
    static final double PROBE_STEP_PX = 0.25;

    /** Edge length below which a ring has no usable probe edge, in pixels. */
    private static final double MIN_PROBE_EDGE_PX = 1.0e-9;

    private RingNesting() {}

    /** Even-odd containment test against one ring. */
    static boolean contains(final double[] xs, final double[] ys, final double px, final double py) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > py) == (ys[j] > py)) {
                continue;
            }
            final double crossX = xs[j] + (py - ys[j]) * (xs[i] - xs[j]) / (ys[i] - ys[j]);
            if (px < crossX) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Nesting depth of every ring, counted at a point that lies inside it.
     *
     * @param rings one {@code {xs, ys}} pair per ring, in any order
     */
    static int[] depths(final List<double[][]> rings) {
        final int[] depths = new int[rings.size()];
        final double[][] probes = new double[rings.size()][];
        for (int i = 0; i < rings.size(); i++) {
            probes[i] = probe(rings.get(i)[0], rings.get(i)[1]);
        }
        for (int i = 0; i < rings.size(); i++) {
            final double[] point = probes[i];
            if (point == null) {
                continue;
            }
            for (int j = 0; j < rings.size(); j++) {
                if (i != j && contains(rings.get(j)[0], rings.get(j)[1], point[0], point[1])) {
                    depths[i]++;
                }
            }
        }
        return depths;
    }

    /**
     * Point that lies strictly inside one ring.
     *
     * <p>The longest edge gives the most room for the inward step, so even a thin ring keeps the
     * probe inside its own material.</p>
     *
     * @return {@code {x, y}}, or {@code null} when the ring has no edge
     */
    static double[] probe(final double[] xs, final double[] ys) {
        int best = -1;
        double bestLength = MIN_PROBE_EDGE_PX;
        for (int i = 0; i < xs.length; i++) {
            final int next = (i + 1) % xs.length;
            final double length = Math.hypot(xs[next] - xs[i], ys[next] - ys[i]);
            if (length > bestLength) {
                bestLength = length;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        final int next = (best + 1) % xs.length;
        final double directionX = (xs[next] - xs[best]) / bestLength;
        final double directionY = (ys[next] - ys[best]) / bestLength;
        final double winding = signedArea(xs, ys) < 0.0 ? -1.0 : 1.0;
        return new double[] {
            0.5 * (xs[best] + xs[next]) - winding * PROBE_STEP_PX * directionY,
            0.5 * (ys[best] + ys[next]) + winding * PROBE_STEP_PX * directionX
        };
    }

    /** Shoelace area of one ring; the sign keeps the winding of the loop. */
    static double signedArea(final double[] xs, final double[] ys) {
        double sum = 0.0;
        for (int i = 0; i < xs.length; i++) {
            final int next = (i + 1) % xs.length;
            sum += xs[i] * ys[next] - xs[next] * ys[i];
        }
        return 0.5 * sum;
    }
}
