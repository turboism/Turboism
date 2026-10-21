package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.Arrays;

/** Reusable horizontal edge buckets for the kernel's strict ray/crossing predicates. */
final class StrictRingIndex {
    private static final int MAX_BUCKETS = 32;
    private final int[] counts = new int[MAX_BUCKETS];
    private final int[] starts = new int[MAX_BUCKETS + 1];
    private final int[] cursors = new int[MAX_BUCKETS];
    private int[] edges = new int[0];
    private int[] firstBucket = new int[0];
    private double[][] ring;
    private double minY;
    private double maxY;
    private double cellHeight;
    private int buckets;
    private boolean prepared;

    /** Invalidates candidate scratch without building buckets for immediately rejected placements. */
    void reset(final double[][] points) {
        ring = points;
        prepared = false;
    }

    /** Rebuilds in O(vertices + bucket references), retaining all scratch storage. */
    void prepare(final double[][] points) {
        ring = points;
        prepared = true;
        minY = Double.POSITIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;
        for (final double[] point : points) {
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }
        buckets = Math.max(1, Math.min(MAX_BUCKETS, points.length / 4));
        cellHeight = (maxY - minY) / buckets;
        if (!(cellHeight > 0)) {
            buckets = 1;
            cellHeight = 1;
        }
        if (firstBucket.length < points.length) {
            firstBucket = new int[points.length];
        }
        Arrays.fill(counts, 0);
        for (int i = 0; i < points.length; i++) {
            final double a = points[i][1];
            final double b = points[(i + 1) % points.length][1];
            final int first = bucket(Math.min(a, b));
            final int last = bucket(Math.max(a, b));
            firstBucket[i] = first;
            for (int cell = first; cell <= last; cell++) {
                counts[cell]++;
            }
        }
        starts[0] = 0;
        for (int cell = 0; cell < buckets; cell++) {
            starts[cell + 1] = starts[cell] + counts[cell];
            cursors[cell] = starts[cell];
        }
        if (edges.length < starts[buckets]) {
            edges = new int[starts[buckets]];
        }
        for (int i = 0; i < points.length; i++) {
            final int last = bucket(Math.max(points[i][1], points[(i + 1) % points.length][1]));
            for (int cell = firstBucket[i]; cell <= last; cell++) {
                edges[cursors[cell]++] = i;
            }
        }
    }

    boolean inside(final double[] point) {
        if (!prepared) prepare(ring);
        if (point[1] < minY || point[1] > maxY) {
            return false;
        }
        boolean odd = false;
        final int cell = bucket(point[1]);
        for (int offset = starts[cell]; offset < starts[cell + 1]; offset++) {
            final int edge = edges[offset];
            // Keep Geom.insideStrict's operand order, including its boundary semantics.
            final double[] vi = ring[(edge + 1) % ring.length];
            final double[] vj = ring[edge];
            if ((vi[1] < point[1] && vj[1] >= point[1] || vj[1] < point[1] && vi[1] >= point[1])
                    && (vi[0] <= point[0] || vj[0] <= point[0])) {
                if (vi[0] + (point[1] - vi[1]) / (vj[1] - vi[1]) * (vj[0] - vi[0]) < point[0]) {
                    odd = !odd;
                }
            }
        }
        return odd;
    }

    boolean crosses(final double[] a, final double[] b) {
        if (!prepared) prepare(ring);
        final double lowY = Math.min(a[1], b[1]);
        final double highY = Math.max(a[1], b[1]);
        if (lowY > maxY + Geom.PRECISION || highY < minY - Geom.PRECISION) {
            return false;
        }
        final double lowX = Math.min(a[0], b[0]);
        final double highX = Math.max(a[0], b[0]);
        final int first = bucket(lowY - Geom.PRECISION);
        final int last = bucket(highY + Geom.PRECISION);
        for (int cell = first; cell <= last; cell++) {
            for (int offset = starts[cell]; offset < starts[cell + 1]; offset++) {
                final int edge = edges[offset];
                // Each edge belongs to several buckets; visit its first shared bucket only.
                if (cell != Math.max(first, firstBucket[edge])) {
                    continue;
                }
                final double[] c = ring[edge];
                final double[] d = ring[(edge + 1) % ring.length];
                if (lowX > Math.max(c[0], d[0]) + Geom.PRECISION || highX < Math.min(c[0], d[0]) - Geom.PRECISION) {
                    continue;
                }
                if (Geom.segmentsCrossStrict(a, b, c, d)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int bucket(final double y) {
        return Math.max(0, Math.min(buckets - 1, (int) ((y - minY) / cellHeight)));
    }
}
