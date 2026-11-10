package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Averages nearby sites after every point exists.
 *
 * <p>Two sites closer than the merge radius become one vertex at the mass-weighted mean of their
 * original positions, so repeated merges stay anchored to the sampled geometry. A merge is only
 * accepted while the plane graph keeps its face count, every ring keeps at least three distinct
 * vertices and stays a simple loop, and no edge starts to cross or overlap another one.</p>
 */
final class SiteMerger {

    private SiteMerger() {}

    /**
     * @param radius candidate merge distance in pixels; not a guaranteed final separation
     */
    static SiteGraph.Snapshot merge(final SiteGraph.Snapshot in, final double radius) {
        final int siteCount = in.siteCount();
        final boolean[] active = new boolean[siteCount];
        Arrays.fill(active, true);
        final boolean[] boundary = new boolean[siteCount];
        for (int i = 0; i < Math.min(in.boundaryCount(), siteCount); i++) {
            boundary[i] = true;
        }
        final double[] mass = new double[siteCount];
        Arrays.fill(mass, 1.0);
        final double[] xs = in.xs().clone();
        final double[] ys = in.ys().clone();
        List<int[]> edges = new ArrayList<>(in.edgeCount());
        final List<ConstraintRole> roles = new ArrayList<>(in.edgeCount());
        for (int e = 0; e < in.edgeCount(); e++) {
            edges.add(new int[] {in.edgeA()[e], in.edgeB()[e]});
            roles.add(in.edgeRoles()[e]);
        }
        List<int[]> rings = new ArrayList<>(in.rings());
        final int faces = in.boundedFaceCount();
        while (true) {
            boolean accepted = false;
            for (final int[] pair : nearbyPairs(xs, ys, active, radius)) {
                final int a = pair[0];
                final int b = pair[1];
                if (!active[a] || !active[b]) {
                    continue;
                }
                final double newX = (xs[a] * mass[a] + xs[b] * mass[b]) / (mass[a] + mass[b]);
                final double newY = (ys[a] * mass[a] + ys[b] * mass[b]) / (mass[a] + mass[b]);
                final List<int[]> candidateEdges = new ArrayList<>(edges.size());
                final List<ConstraintRole> candidateRoles = new ArrayList<>(edges.size());
                replacedEdges(edges, roles, a, b, candidateEdges, candidateRoles);
                final List<int[]> candidateRings = replaceInRings(rings, a, b, siteCount);
                if (candidateRings == null) {
                    continue;
                }
                if (boundedFaces(candidateEdges) != faces) {
                    continue;
                }
                final double[] candidateXs = xs.clone();
                final double[] candidateYs = ys.clone();
                candidateXs[a] = newX;
                candidateYs[a] = newY;
                if (!remainsEmbedded(candidateXs, candidateYs, candidateEdges, a)) {
                    continue;
                }
                if (!DomainRingAudit.acceptsRingRewrite(candidateXs, candidateYs, rings, candidateRings, b, a)) {
                    continue;
                }
                xs[a] = newX;
                ys[a] = newY;
                mass[a] += mass[b];
                active[b] = false;
                if (boundary[b]) {
                    boundary[a] = true;
                }
                boundary[b] = false;
                edges = candidateEdges;
                roles.clear();
                roles.addAll(candidateRoles);
                rings = candidateRings;
                accepted = true;
            }
            if (!accepted) {
                break;
            }
        }
        return compact(in, xs, ys, active, boundary, edges, roles, rings);
    }

    /** Moves the endpoints of {@code b} onto {@code a} and re-points the collapsed edge. */
    private static void replacedEdges(
            final List<int[]> edges,
            final List<ConstraintRole> roles,
            final int a,
            final int b,
            final List<int[]> out,
            final List<ConstraintRole> outRoles) {
        final Set<Long> keys = new HashSet<>();
        for (int e = 0; e < edges.size(); e++) {
            final int[] edge = edges.get(e);
            if ((edge[0] == a && edge[1] == b) || (edge[0] == b && edge[1] == a)) {
                continue;
            }
            final int first = edge[0] == b ? a : edge[0];
            final int second = edge[1] == b ? a : edge[1];
            if (first == second) {
                continue;
            }
            final int low = Math.min(first, second);
            final int high = Math.max(first, second);
            if (keys.add(((long) low << 32) | (high & 0xFFFF_FFFFL))) {
                out.add(new int[] {low, high});
                outRoles.add(roles.get(e));
            }
        }
    }

    private static List<int[]> replaceInRings(final List<int[]> rings, final int a, final int b, final int siteCount) {
        final List<int[]> out = new ArrayList<>(rings.size());
        for (final int[] ring : rings) {
            final int[] replaced = new int[ring.length];
            int size = 0;
            for (final int vertex : ring) {
                final int mapped = vertex == b ? a : vertex;
                if (size > 0 && replaced[size - 1] == mapped) {
                    continue;
                }
                replaced[size++] = mapped;
            }
            if (size > 1 && replaced[0] == replaced[size - 1]) {
                size--;
            }
            if (size < 3) {
                return null;
            }
            final boolean[] seen = new boolean[siteCount];
            int distinct = 0;
            for (int i = 0; i < size; i++) {
                if (!seen[replaced[i]]) {
                    seen[replaced[i]] = true;
                    distinct++;
                }
            }
            if (distinct < 3) {
                return null;
            }
            out.add(Arrays.copyOf(replaced, size));
        }
        return out;
    }

    private static int boundedFaces(final List<int[]> edges) {
        final int maxSite = maxSite(edges);
        final int[] parent = new int[maxSite + 1];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (final int[] edge : edges) {
            union(parent, edge[0], edge[1]);
        }
        final boolean[] incident = new boolean[maxSite + 1];
        final boolean[] roots = new boolean[maxSite + 1];
        int components = 0;
        for (final int[] edge : edges) {
            incident[edge[0]] = true;
            incident[edge[1]] = true;
            final int root = find(parent, edge[0]);
            if (!roots[root]) {
                roots[root] = true;
                components++;
            }
        }
        int vertices = 0;
        for (final boolean touched : incident) {
            if (touched) {
                vertices++;
            }
        }
        return edges.size() - vertices + components;
    }

    private static int maxSite(final List<int[]> edges) {
        int max = 0;
        for (final int[] edge : edges) {
            max = Math.max(max, Math.max(edge[0], edge[1]));
        }
        return max;
    }

    private static boolean remainsEmbedded(
            final double[] xs, final double[] ys, final List<int[]> edges, final int moved) {
        for (int i = 0; i < edges.size(); i++) {
            final int[] first = edges.get(i);
            if (first[0] != moved && first[1] != moved) {
                continue;
            }
            for (int j = 0; j < edges.size(); j++) {
                if (i == j) {
                    continue;
                }
                final int[] second = edges.get(j);
                if (second[0] == first[0] || second[0] == first[1] || second[1] == first[0] || second[1] == first[1]) {
                    continue;
                }
                if (!boxesOverlap(xs, ys, first, second)) {
                    continue;
                }
                final double ax = xs[first[0]];
                final double ay = ys[first[0]];
                final double bx = xs[first[1]];
                final double by = ys[first[1]];
                final double cx = xs[second[0]];
                final double cy = ys[second[0]];
                final double dx = xs[second[1]];
                final double dy = ys[second[1]];
                if (PixelGeometry.properCrossing(ax, ay, bx, by, cx, cy, dx, dy)
                        || PixelGeometry.collinearOverlap(ax, ay, bx, by, cx, cy, dx, dy)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean boxesOverlap(final double[] xs, final double[] ys, final int[] first, final int[] second) {
        final double firstMinX = Math.min(xs[first[0]], xs[first[1]]);
        final double firstMaxX = Math.max(xs[first[0]], xs[first[1]]);
        final double firstMinY = Math.min(ys[first[0]], ys[first[1]]);
        final double firstMaxY = Math.max(ys[first[0]], ys[first[1]]);
        final double secondMinX = Math.min(xs[second[0]], xs[second[1]]);
        final double secondMaxX = Math.max(xs[second[0]], xs[second[1]]);
        final double secondMinY = Math.min(ys[second[0]], ys[second[1]]);
        final double secondMaxY = Math.max(ys[second[0]], ys[second[1]]);
        return firstMinX <= secondMaxX && secondMinX <= firstMaxX && firstMinY <= secondMaxY && secondMinY <= firstMaxY;
    }

    private static List<int[]> nearbyPairs(
            final double[] xs, final double[] ys, final boolean[] active, final double radius) {
        final Map<Long, List<Integer>> grid = new HashMap<>();
        final List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (!active[i]) {
                continue;
            }
            grid.computeIfAbsent(cellKey(xs[i], ys[i], radius), ignored -> new ArrayList<>())
                    .add(i);
        }
        for (int i = 0; i < xs.length; i++) {
            if (!active[i]) {
                continue;
            }
            final long cellX = (long) Math.floor(xs[i] / radius);
            final long cellY = (long) Math.floor(ys[i] / radius);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    final List<Integer> bucket = grid.get((cellX + dx) << 32 ^ ((cellY + dy) & 0xFFFF_FFFFL));
                    if (bucket == null) {
                        continue;
                    }
                    for (final int candidate : bucket) {
                        if (candidate <= i) {
                            continue;
                        }
                        final double distance = Math.hypot(xs[i] - xs[candidate], ys[i] - ys[candidate]);
                        if (distance < radius) {
                            pairs.add(new int[] {i, candidate});
                        }
                    }
                }
            }
        }
        pairs.sort((first, second) -> {
            final double distanceFirst = Math.hypot(xs[first[0]] - xs[first[1]], ys[first[0]] - ys[first[1]]);
            final double distanceSecond = Math.hypot(xs[second[0]] - xs[second[1]], ys[second[0]] - ys[second[1]]);
            return Double.compare(distanceFirst, distanceSecond);
        });
        return pairs;
    }

    private static long cellKey(final double x, final double y, final double radius) {
        final long cellX = (long) Math.floor(x / radius);
        final long cellY = (long) Math.floor(y / radius);
        return (cellX << 32) ^ (cellY & 0xFFFF_FFFFL);
    }

    private static SiteGraph.Snapshot compact(
            final SiteGraph.Snapshot in,
            final double[] xs,
            final double[] ys,
            final boolean[] active,
            final boolean[] boundary,
            final List<int[]> edges,
            final List<ConstraintRole> roles,
            final List<int[]> rings) {
        final List<Integer> order = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            if (active[i] && boundary[i]) {
                order.add(i);
            }
        }
        final int boundaryCount = order.size();
        for (int i = 0; i < xs.length; i++) {
            if (active[i] && !boundary[i]) {
                order.add(i);
            }
        }
        final int[] remap = new int[xs.length];
        Arrays.fill(remap, -1);
        final double[] outX = new double[order.size()];
        final double[] outY = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            final int source = order.get(i);
            remap[source] = i;
            outX[i] = xs[source];
            outY[i] = ys[source];
        }
        final int[] edgeA = new int[edges.size()];
        final int[] edgeB = new int[edges.size()];
        final ConstraintRole[] edgeRoles = new ConstraintRole[edges.size()];
        int kept = 0;
        for (int e = 0; e < edges.size(); e++) {
            final int[] edge = edges.get(e);
            final int a = remap[edge[0]];
            final int b = remap[edge[1]];
            if (a < 0 || b < 0 || a == b) {
                continue;
            }
            edgeA[kept] = Math.min(a, b);
            edgeB[kept] = Math.max(a, b);
            edgeRoles[kept] = roles.get(e);
            kept++;
        }
        final List<int[]> outRings = new ArrayList<>(rings.size());
        for (final int[] ring : rings) {
            final List<Integer> mapped = new ArrayList<>(ring.length);
            for (final int vertex : ring) {
                final int target = vertex < remap.length ? remap[vertex] : -1;
                if (target >= 0 && (mapped.isEmpty() || mapped.get(mapped.size() - 1) != target)) {
                    mapped.add(target);
                }
            }
            if (!mapped.isEmpty() && mapped.get(0).equals(mapped.get(mapped.size() - 1))) {
                mapped.remove(mapped.size() - 1);
            }
            if (mapped.size() >= 3) {
                final int[] packed = new int[mapped.size()];
                for (int i = 0; i < packed.length; i++) {
                    packed[i] = mapped.get(i);
                }
                outRings.add(packed);
            }
        }
        return new SiteGraph.Snapshot(
                        outX,
                        outY,
                        Arrays.copyOf(edgeA, kept),
                        Arrays.copyOf(edgeB, kept),
                        Arrays.copyOf(edgeRoles, kept),
                        boundaryCount)
                .withRings(outRings);
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
}
