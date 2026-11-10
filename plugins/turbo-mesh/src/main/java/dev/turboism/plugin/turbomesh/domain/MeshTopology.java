package dev.turboism.plugin.turbomesh.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boundary extraction for a triangle soup. An undirected edge used by exactly one triangle lies
 * on the mesh boundary; chaining those edges in the owning triangle's winding direction produces
 * closed loops with a consistent orientation (outer loops opposite to holes under consistent
 * winding).
 */
public final class MeshTopology {

    private MeshTopology() {}

    /**
     * Returns the boundary loops of the mesh as closed vertex-index rings. Each ring lists every
     * vertex once (the last vertex connects back to the first). Returns an empty list when no
     * triangle leaves an edge unshared. Non-manifold pinches terminate the affected ring early —
     * the prefix up to the pinch is still returned.
     */
    public static List<int[]> boundaryLoops(final List<Integer> triangleIndices) {
        final Map<Long, Integer> useCount = new HashMap<>();
        final Map<Long, int[]> oriented = new HashMap<>();
        for (int t = 0; t + 2 < triangleIndices.size(); t += 3) {
            final int[] verts = {triangleIndices.get(t), triangleIndices.get(t + 1), triangleIndices.get(t + 2)};
            for (int e = 0; e < 3; e++) {
                final int a = verts[e];
                final int b = verts[(e + 1) % 3];
                final long key = undirectedKey(a, b);
                if (useCount.merge(key, 1, Integer::sum) == 1) {
                    oriented.putIfAbsent(key, new int[] {a, b});
                }
            }
        }
        final Map<Integer, List<Integer>> outgoing = new HashMap<>();
        for (final Map.Entry<Long, int[]> entry : oriented.entrySet()) {
            if (useCount.get(entry.getKey()) != 1) {
                continue;
            }
            final int[] ab = entry.getValue();
            outgoing.computeIfAbsent(ab[0], k -> new ArrayList<>()).add(ab[1]);
        }
        final List<int[]> loops = new ArrayList<>();
        final Set<Long> consumedDirected = new HashSet<>();
        for (final Map.Entry<Integer, List<Integer>> entry : outgoing.entrySet()) {
            final int from = entry.getKey();
            for (final int to : entry.getValue()) {
                if (!consumedDirected.add(directedKey(from, to))) {
                    continue;
                }
                final List<Integer> ring = new ArrayList<>();
                ring.add(from);
                int cursor = to;
                while (cursor != from) {
                    ring.add(cursor);
                    final List<Integer> nexts = outgoing.get(cursor);
                    if (nexts == null) {
                        break;
                    }
                    int next = -1;
                    for (final int candidate : nexts) {
                        if (consumedDirected.add(directedKey(cursor, candidate))) {
                            next = candidate;
                            break;
                        }
                    }
                    if (next < 0) {
                        break;
                    }
                    cursor = next;
                }
                if (ring.size() >= 3 && cursor == from) {
                    loops.add(toArray(ring));
                }
            }
        }
        return loops;
    }

    private static int[] toArray(final List<Integer> ring) {
        final int[] out = new int[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            out[i] = ring.get(i);
        }
        return out;
    }

    static long undirectedKey(final int a, final int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
    }

    private static long directedKey(final int a, final int b) {
        return ((long) a << 32) | (b & 0xFFFF_FFFFL);
    }
}
