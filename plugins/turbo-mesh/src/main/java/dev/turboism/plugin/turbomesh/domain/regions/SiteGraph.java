package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared plane graph of mesh sites and hard constraint edges.
 *
 * <p>Sites are deduplicated at {@link #SITE_TOLERANCE}, so a junction computed twice still yields
 * one vertex and one closed ring. Edges are undirected and unique; the first role assigned to a
 * pair wins because a shared border keeps the role of the path that produced it first.</p>
 */
final class SiteGraph {

    /** Distance below which two requested sites become one vertex, in pixels. */
    static final double SITE_TOLERANCE = 1.0e-6;

    private static final int INITIAL_SITES = 256;

    private final List<double[]> sites = new ArrayList<>(INITIAL_SITES);
    private final Map<Long, List<Integer>> buckets = new HashMap<>();
    private final List<int[]> edgeEnds = new ArrayList<>();
    private final List<ConstraintRole> edgeRoles = new ArrayList<>();
    private final Map<Long, Integer> edgeIndex = new HashMap<>();
    private final List<List<Integer>> adjacency = new ArrayList<>(INITIAL_SITES);

    private int boundaryCount = -1;

    /** Registers a site and returns its index, merging with an existing site when close enough. */
    int site(final double x, final double y) {
        final long qx = Math.round(x / SITE_TOLERANCE);
        final long qy = Math.round(y / SITE_TOLERANCE);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                final List<Integer> bucket = buckets.get(key(qx + dx, qy + dy));
                if (bucket == null) {
                    continue;
                }
                for (final int candidate : bucket) {
                    final double[] site = sites.get(candidate);
                    if (Math.abs(site[0] - x) <= SITE_TOLERANCE && Math.abs(site[1] - y) <= SITE_TOLERANCE) {
                        return candidate;
                    }
                }
            }
        }
        final int index = sites.size();
        sites.add(new double[] {x, y});
        buckets.computeIfAbsent(key(qx, qy), ignored -> new ArrayList<>()).add(index);
        return index;
    }

    /** Adds every segment of the path and tags the created edges with the path role. */
    void addPath(final Polyline line, final ConstraintRole role) {
        for (int i = 0; i < line.segmentCount(); i++) {
            final int next = line.nextIndex(i);
            final int a = site(line.x(i), line.y(i));
            final int b = site(line.x(next), line.y(next));
            if (a != b) {
                edge(a, b, role);
            }
        }
    }

    void edge(final int a, final int b, final ConstraintRole role) {
        final long edgeKey = ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
        if (edgeIndex.putIfAbsent(edgeKey, edgeEnds.size()) != null) {
            return;
        }
        edgeEnds.add(new int[] {Math.min(a, b), Math.max(a, b)});
        edgeRoles.add(role);
        neighbours(a).add(b);
        neighbours(b).add(a);
    }

    /** Sites that an existing edge connects to {@code site}, in edge insertion order. */
    List<Integer> neighbours(final int site) {
        while (adjacency.size() <= site) {
            adjacency.add(new ArrayList<>(2));
        }
        return adjacency.get(site);
    }

    /** Marks the current site count as the boundary count; later sites count as interior. */
    void sealBoundary() {
        boundaryCount = sites.size();
    }

    int boundaryCount() {
        return boundaryCount < 0 ? sites.size() : boundaryCount;
    }

    int siteCount() {
        return sites.size();
    }

    double x(final int site) {
        return sites.get(site)[0];
    }

    double y(final int site) {
        return sites.get(site)[1];
    }

    /** Freezes the graph into arrays the merger can copy per candidate. */
    Snapshot snapshot() {
        final double[] xs = new double[sites.size()];
        final double[] ys = new double[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            xs[i] = sites.get(i)[0];
            ys[i] = sites.get(i)[1];
        }
        final int[] first = new int[edgeEnds.size()];
        final int[] second = new int[edgeEnds.size()];
        final ConstraintRole[] roles = new ConstraintRole[edgeEnds.size()];
        for (int i = 0; i < edgeEnds.size(); i++) {
            first[i] = edgeEnds.get(i)[0];
            second[i] = edgeEnds.get(i)[1];
            roles[i] = edgeRoles.get(i);
        }
        return new Snapshot(xs, ys, first, second, roles, boundaryCount());
    }

    private static long key(final long qx, final long qy) {
        return (qx << 32) ^ (qy & 0xFFFF_FFFFL);
    }

    /**
     * Frozen graph.
     *
     * @param rings domain rings as site-index loops, the silhouette first
     */
    record Snapshot(
            double[] xs,
            double[] ys,
            int[] edgeA,
            int[] edgeB,
            ConstraintRole[] edgeRoles,
            int boundaryCount,
            List<int[]> rings) {

        Snapshot(
                final double[] xs,
                final double[] ys,
                final int[] edgeA,
                final int[] edgeB,
                final ConstraintRole[] edgeRoles,
                final int boundaryCount) {
            this(xs, ys, edgeA, edgeB, edgeRoles, boundaryCount, List.of());
        }

        Snapshot withRings(final List<int[]> domainRings) {
            final List<int[]> copy = new ArrayList<>(domainRings.size());
            for (final int[] ring : domainRings) {
                copy.add(ring.clone());
            }
            return new Snapshot(xs, ys, edgeA, edgeB, edgeRoles, boundaryCount, List.copyOf(copy));
        }

        double x(final int site) {
            return xs[site];
        }

        double y(final int site) {
            return ys[site];
        }

        int siteCount() {
            return xs.length;
        }

        int edgeCount() {
            return edgeA.length;
        }

        /** Distinct vertices that at least one edge touches. */
        int incidentSiteCount() {
            final int[] touched = touchedSites();
            int count = 0;
            for (final int value : touched) {
                if (value > 0) {
                    count++;
                }
            }
            return count;
        }

        /** Connected components of the edge graph. */
        int componentCount() {
            final int[] parent = new int[xs.length];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
            }
            final boolean[] roots = new boolean[xs.length];
            for (int e = 0; e < edgeA.length; e++) {
                union(parent, edgeA[e], edgeB[e]);
            }
            int components = 0;
            for (int e = 0; e < edgeA.length; e++) {
                components += rootOnce(roots, find(parent, edgeA[e]));
                components += rootOnce(roots, find(parent, edgeB[e]));
            }
            return components;
        }

        /** Number of bounded faces, by Euler's formula for a planar graph. */
        int boundedFaceCount() {
            return edgeA.length - incidentSiteCount() + componentCount();
        }

        int[] touchedSites() {
            final int[] touched = new int[xs.length];
            for (int e = 0; e < edgeA.length; e++) {
                touched[edgeA[e]] = 1;
                touched[edgeB[e]] = 1;
            }
            return touched;
        }

        private static int find(final int[] parent, final int start) {
            int root = start;
            while (parent[root] != root) {
                root = parent[root];
            }
            int cursor = start;
            while (parent[cursor] != root) {
                final int next = parent[cursor];
                parent[cursor] = root;
                cursor = next;
            }
            return root;
        }

        private static void union(final int[] parent, final int a, final int b) {
            final int rootA = find(parent, a);
            final int rootB = find(parent, b);
            if (rootA != rootB) {
                parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
            }
        }

        private static int rootOnce(final boolean[] roots, final int root) {
            if (roots[root]) {
                return 0;
            }
            roots[root] = true;
            return 1;
        }
    }
}
