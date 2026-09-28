package dev.turboism.validation.edgeindex;

/**
 * Replays the edge-mutation portion of the official apply loop
 * ({@code b.a(GEditableMesh2, List, Z, j/a)}): after the triangulation result
 * {@code [[I} is fetched and {@code clearAutoTriangulation()} runs, the loop
 * issues {@code addEdgeIfNotExists} three times per triangle — (v0,v1),
 * (v1,v2), (v2,v0) — discarding the returned index. The six {@code j/a.d()}
 * progress calls all sit outside this loop; the two bordering the edge-mutation
 * region are modeled here so event sequences stay comparable.
 */
final class ApplyRunner {

    private ApplyRunner() {
    }

    static void applyTriangles(final EdgeOps ops, final int[][] triangles) {
        ops.progress(); // j/a.d() after c.f()
        ops.clearAutoTriangulation();
        ops.beginBatch();
        for (final int[] tri : triangles) {
            ops.addEdgeIfNotExists(tri[0], tri[1], EdgeType.AUTO_TRIANGULATION);
            ops.addEdgeIfNotExists(tri[1], tri[2], EdgeType.AUTO_TRIANGULATION);
            ops.addEdgeIfNotExists(tri[2], tri[0], EdgeType.AUTO_TRIANGULATION);
        }
        ops.endBatch();
        ops.progress(); // j/a.d() after the loop
    }
}
