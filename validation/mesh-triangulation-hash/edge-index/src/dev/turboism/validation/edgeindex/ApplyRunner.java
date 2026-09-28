package dev.turboism.validation.edgeindex;

import dev.turboism.validation.edgeindex.EdgeOps.ModeledCancel;

/**
 * Replays the edge-mutation portion of the official apply loop
 * ({@code b.a(GEditableMesh2, List, Z, j/a)}): after {@code progress.d()} at
 * bytecode offset 190 and {@code clearAutoTriangulation()} at 197, the loop
 * issues {@code addEdgeIfNotExists} three times per triangle — (v0,v1),
 * (v1,v2), (v2,v0) — discarding the returned index; {@code progress.d()} at
 * offset 312 follows the loop. The other four d() sites sit around the
 * triangulation setup/compute and are outside this modeled slice.
 *
 * <p>Per-triangle read order is bytecode-faithful: both endpoint reads happen
 * immediately before each {@code invokestatic} — so a null row throws NPE
 * before any edge call, a length-2 row completes edge (v0,v1) and then throws
 * AIOOBE on the (v1,v2) read, etc.</p>
 */
final class ApplyRunner {

    private ApplyRunner() {
    }

    /** One edge of a triangle, with bytecode-faithful argument read order. */
    static void edge(final EdgeOps ops, final int[] tri, final int slot) {
        switch (slot) {
            case 0 -> ops.addEdgeIfNotExists(tri[0], tri[1], EdgeType.AUTO_TRIANGULATION);
            case 1 -> ops.addEdgeIfNotExists(tri[1], tri[2], EdgeType.AUTO_TRIANGULATION);
            case 2 -> ops.addEdgeIfNotExists(tri[2], tri[0], EdgeType.AUTO_TRIANGULATION);
            default -> throw new AssertionError("slot");
        }
    }

    /**
     * Single-implementation replay (benchmark path). {@code endBatch} runs in
     * finally: the index is candidate-internal scratch state whose release must
     * not depend on normal termination, and clearing it emits no host-visible
     * events.
     */
    static void applyTriangles(final EdgeOps ops, final int[][] triangles)
            throws ModeledCancel {
        ops.progress();
        ops.clearAutoTriangulation();
        ops.beginBatch();
        try {
            for (final int[] tri : triangles) {
                edge(ops, tri, 0);
                edge(ops, tri, 1);
                edge(ops, tri, 2);
            }
        } finally {
            ops.endBatch();
        }
        ops.progress();
    }
}
