package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Material outline assembled from the alpha border chains.
 *
 * <p>Internal texture borders cut the silhouette into several chains, and an alpha hole inherits
 * the label pair of every region that touches it. Chaining all alpha border segments by their
 * shared endpoints therefore rebuilds every exterior ring and every hole ring, and each ring vertex
 * is a site the mesh will use.</p>
 *
 * <p>The rings stay in one set. Nesting depth, not ring size, decides which of them bound material,
 * so two parts of one layer and a hole inside one part are all described correctly.</p>
 *
 * <p>Border chains arrive already oriented, with one face on a fixed side. The ring walk keeps
 * that face on the left at every junction, because an arbitrary junction pairing folds a thin
 * silhouette into a spiral and the resulting ring no longer encloses its spikes.</p>
 */
final class DomainOutline {

    /** Endpoints closer than this distance are the same junction, in pixels. */
    static final double NODE_TOLERANCE = 1.0e-6;

    private DomainOutline() {}

    /**
     * @param borders shared texture and alpha borders in pixel coordinates
     * @return material outline with islands and holes, or {@code null} when no alpha border closes
     */
    static PolygonShape of(final List<BoundaryPath> borders) {
        final Nodes nodes = new Nodes();
        final List<int[]> edges = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        for (final BoundaryPath path : borders) {
            if (!path.isOutline()) {
                continue;
            }
            final Polyline line = path.line();
            for (int i = 0; i < line.segmentCount(); i++) {
                final int next = line.nextIndex(i);
                final int a = nodes.node(line.x(i), line.y(i));
                final int b = nodes.node(line.x(next), line.y(next));
                if (a == b) {
                    continue;
                }
                final int low = Math.min(a, b);
                final int high = Math.max(a, b);
                if (seen.add(((long) low << 32) | (high & 0xFFFF_FFFFL))) {
                    edges.add(new int[] {a, b});
                }
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        final List<Polyline> loops = new ArrayList<>();
        for (final int[] ring : chainedLoops(edges, nodes.xs(), nodes.ys())) {
            loops.add(nodes.polyline(ring));
        }
        if (loops.isEmpty()) {
            return null;
        }
        loops.sort((first, second) -> Double.compare(Math.abs(second.signedArea()), Math.abs(first.signedArea())));
        return new PolygonShape(loops);
    }

    /**
     * Keeps the closed chains of a directed border walk; an open chain cannot bound material.
     *
     * @param edges directed index pairs in border order
     * @param nodeXs node coordinates of the lattice
     * @param nodeYs node coordinates of the lattice
     * @return closed rings, each listing every vertex once
     */
    private static List<int[]> chainedLoops(final List<int[]> edges, final double[] nodeXs, final double[] nodeYs) {
        final List<int[]> loops = new ArrayList<>();
        for (final int[] chain : DirectedBoundary.chains(edges, node -> nodeXs[node], node -> nodeYs[node])) {
            if (chain.length <= 3 || chain[0] != chain[chain.length - 1]) {
                continue;
            }
            loops.add(Arrays.copyOf(chain, chain.length - 1));
        }
        return loops;
    }

    /** Growth-ordered registry that merges junctions closer than {@link #NODE_TOLERANCE}. */
    private static final class Nodes {

        private final Map<Long, List<Integer>> buckets = new HashMap<>();
        private int size;
        private double[] xs = new double[128];
        private double[] ys = new double[128];

        int node(final double x, final double y) {
            final long qx = Math.round(x / NODE_TOLERANCE);
            final long qy = Math.round(y / NODE_TOLERANCE);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    final List<Integer> bucket = buckets.get(key(qx + dx, qy + dy));
                    if (bucket == null) {
                        continue;
                    }
                    for (final int candidate : bucket) {
                        if (Math.abs(xs[candidate] - x) <= NODE_TOLERANCE
                                && Math.abs(ys[candidate] - y) <= NODE_TOLERANCE) {
                            return candidate;
                        }
                    }
                }
            }
            if (size == xs.length) {
                xs = Arrays.copyOf(xs, size * 2);
                ys = Arrays.copyOf(ys, size * 2);
            }
            xs[size] = x;
            ys[size] = y;
            buckets.computeIfAbsent(key(qx, qy), ignored -> new ArrayList<>()).add(size);
            return size++;
        }

        Polyline polyline(final int[] ring) {
            final double[] ringXs = new double[ring.length];
            final double[] ringYs = new double[ring.length];
            for (int i = 0; i < ring.length; i++) {
                ringXs[i] = xs[ring[i]];
                ringYs[i] = ys[ring[i]];
            }
            return new Polyline(ringXs, ringYs, true);
        }

        double[] xs() {
            return Arrays.copyOf(xs, size);
        }

        double[] ys() {
            return Arrays.copyOf(ys, size);
        }

        private static long key(final long qx, final long qy) {
            return (qx << 32) ^ (qy & 0xFFFF_FFFFL);
        }
    }
}
