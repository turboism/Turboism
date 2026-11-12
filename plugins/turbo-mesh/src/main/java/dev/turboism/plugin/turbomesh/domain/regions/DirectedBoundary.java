package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * Traces directed boundary segments into chains.
 *
 * <p>Each segment is oriented with one face on a fixed side, so a junction has exactly one
 * continuation: the segment that turns furthest left keeps that face. An arbitrary pairing swaps
 * the two faces and folds a thin silhouette into a spiral.</p>
 *
 * <p>An open chain starts at its source, where more segments leave than enter. Starting in its
 * middle would split the chain and pin an invented junction on the silhouette.</p>
 */
final class DirectedBoundary {

    private DirectedBoundary() {}

    /**
     * @param edges directed node index pairs
     * @param nodeX coordinate of one node
     * @param nodeY coordinate of one node
     * @return chains of node indices; a closed chain repeats its start node at the end
     */
    static List<int[]> chains(
            final List<int[]> edges, final IntToDoubleFunction nodeX, final IntToDoubleFunction nodeY) {
        final Map<Integer, List<Integer>> outgoing = new HashMap<>();
        final Map<Integer, Integer> incomingCount = new HashMap<>();
        for (int e = 0; e < edges.size(); e++) {
            outgoing.computeIfAbsent(edges.get(e)[0], ignored -> new ArrayList<>())
                    .add(e);
            incomingCount.merge(edges.get(e)[1], 1, Integer::sum);
        }
        final List<Integer> starts = new ArrayList<>(edges.size());
        for (int e = 0; e < edges.size(); e++) {
            starts.add(e);
        }
        starts.sort((first, second) -> Boolean.compare(
                isSource(edges.get(second), outgoing, incomingCount),
                isSource(edges.get(first), outgoing, incomingCount)));
        final boolean[] used = new boolean[edges.size()];
        final List<int[]> chains = new ArrayList<>();
        for (final int start : starts) {
            if (used[start]) {
                continue;
            }
            used[start] = true;
            final int origin = edges.get(start)[0];
            final List<Integer> chain = new ArrayList<>();
            chain.add(origin);
            int previous = origin;
            int current = edges.get(start)[1];
            while (true) {
                chain.add(current);
                if (current == origin) {
                    break;
                }
                final int next = nextEdge(edges, outgoing.get(current), used, previous, current, nodeX, nodeY);
                if (next < 0 || chain.size() > edges.size()) {
                    break;
                }
                used[next] = true;
                previous = current;
                current = edges.get(next)[1];
            }
            chains.add(toArray(chain));
        }
        return chains;
    }

    /** True when more segments leave the start node than enter it. */
    private static boolean isSource(
            final int[] edge, final Map<Integer, List<Integer>> outgoing, final Map<Integer, Integer> incomingCount) {
        final int node = edge[0];
        return outgoing.get(node).size() > incomingCount.getOrDefault(node, 0);
    }

    /**
     * Index of the unused outgoing segment that keeps the current face on the left, or {@code -1}.
     *
     * @param previous node the chain arrived from
     * @param current node the chain stands on
     */
    private static int nextEdge(
            final List<int[]> edges,
            final List<Integer> candidates,
            final boolean[] used,
            final int previous,
            final int current,
            final IntToDoubleFunction nodeX,
            final IntToDoubleFunction nodeY) {
        if (candidates == null) {
            return -1;
        }
        final double incomingX = nodeX.applyAsDouble(current) - nodeX.applyAsDouble(previous);
        final double incomingY = nodeY.applyAsDouble(current) - nodeY.applyAsDouble(previous);
        int best = -1;
        double bestTurn = Double.POSITIVE_INFINITY;
        for (final int candidate : candidates) {
            if (used[candidate]) {
                continue;
            }
            final int target = edges.get(candidate)[1];
            final double turn = PixelGeometry.leftTurn(
                    incomingX,
                    incomingY,
                    nodeX.applyAsDouble(target) - nodeX.applyAsDouble(current),
                    nodeY.applyAsDouble(target) - nodeY.applyAsDouble(current));
            if (turn < bestTurn) {
                bestTurn = turn;
                best = candidate;
            }
        }
        return best;
    }

    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
