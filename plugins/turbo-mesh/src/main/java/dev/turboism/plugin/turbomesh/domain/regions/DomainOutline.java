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
                    edges.add(new int[] {low, high});
                }
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        final List<Polyline> loops = new ArrayList<>();
        for (final int[] ring : chainedLoops(edges)) {
            loops.add(nodes.polyline(ring));
        }
        if (loops.isEmpty()) {
            return null;
        }
        loops.sort((first, second) -> Double.compare(Math.abs(second.signedArea()), Math.abs(first.signedArea())));
        return new PolygonShape(loops);
    }

    /**
     * Chains edges that border the material into closed vertex loops; open chains drop out.
     *
     * @param edges undirected index pairs
     * @return closed rings in input order, each listing every vertex once
     */
    static List<int[]> chainedLoops(final List<int[]> edges) {
        final Map<Integer, List<Integer>> incident = new HashMap<>();
        for (int e = 0; e < edges.size(); e++) {
            incident.computeIfAbsent(edges.get(e)[0], ignored -> new ArrayList<>())
                    .add(e);
            incident.computeIfAbsent(edges.get(e)[1], ignored -> new ArrayList<>())
                    .add(e);
        }
        final boolean[] used = new boolean[edges.size()];
        final List<int[]> loops = new ArrayList<>();
        for (int start = 0; start < edges.size(); start++) {
            if (used[start]) {
                continue;
            }
            used[start] = true;
            final List<Integer> ring = new ArrayList<>();
            final int origin = edges.get(start)[0];
            ring.add(origin);
            int current = edges.get(start)[1];
            boolean closed = true;
            while (current != origin) {
                ring.add(current);
                final int next = unusedEdge(incident.get(current), used);
                if (next < 0) {
                    closed = false;
                    break;
                }
                used[next] = true;
                current = edges.get(next)[0] == current ? edges.get(next)[1] : edges.get(next)[0];
                if (ring.size() > edges.size() + 1) {
                    closed = false;
                    break;
                }
            }
            if (!closed || ring.size() < 3) {
                continue;
            }
            final int[] packed = new int[ring.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = ring.get(i);
            }
            loops.add(packed);
        }
        return loops;
    }

    private static int unusedEdge(final List<Integer> candidates, final boolean[] used) {
        for (final int edge : candidates) {
            if (!used[edge]) {
                return edge;
            }
        }
        return -1;
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

        private static long key(final long qx, final long qy) {
            return (qx << 32) ^ (qy & 0xFFFF_FFFFL);
        }
    }
}
