package dev.turboism.validation.edgeindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Synthetic cost comparison for the batch-local edge index candidate.
 *
 * <p>Measures <b>comparison count</b> (endpoint-pair comparisons for the native
 * linear scan, map probes + index-build scan for the candidate) and wall time on
 * synthetic workloads, including index construction and allocation. Results are
 * JVM-simulation figures only: they must not be extrapolated to host cost, the
 * real {@code a.a(progress)} triangulation compute still runs inside the apply
 * path and is not covered here, and sizes are synthetic — not taken from any
 * measured mesh.</p>
 */
public final class EdgeIndexBenchmark {

    private EdgeIndexBenchmark() {
    }

    /** Deterministic grid-mesh triangulation: shared edges → realistic hit mix. */
    private static int[][] gridTriangles(final int cols, final int rows) {
        final int w = cols + 1;
        final List<int[]> tris = new ArrayList<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                final int v = y * w + x;
                tris.add(new int[] {v, v + 1, v + w});
                tris.add(new int[] {v + 1, v + w + 1, v + w});
            }
        }
        return tris.toArray(new int[0][]);
    }

    /** Pre-existing edge list: deterministic ids, mixed types, some duplicates. */
    private static List<MEdge> initialEdges(final int count, final long seed) {
        final Random rng = new Random(seed);
        final List<MEdge> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int a = rng.nextInt(400);
            list.add(new MEdge(a, a + 1 + rng.nextInt(3),
                EdgeType.values()[rng.nextInt(4)])); // excludes NOT_INITIALIZED
        }
        return list;
    }

    private static long[] runOnce(final EdgeOps ops, final List<MEdge> initial,
                                  final int[][] triangles) {
        ops.edges.addAll(initial);
        final long start = System.nanoTime();
        ApplyRunner.applyTriangles(ops, triangles);
        final long nanos = System.nanoTime() - start;
        return new long[] {nanos, ops.comparisons};
    }

    public static void main(final String[] args) {
        final int[][] sizes = {
            {0, 8, 8},       // empty list, tiny batch
            {256, 24, 24},   // small: 256 initial, 1152 triangles
            {1024, 48, 48},  // medium: 1024 initial, 4608 triangles
            {2048, 80, 60},  // large: 2048 initial, 9600 triangles
        };
        System.out.println("size=E0xgridTriangles nativeNs indexedNs "
            + "nativeCmp indexedCmp idxBuildDupes");
        for (final int[] s : sizes) {
            final int e0 = s[0];
            final int[][] triangles = gridTriangles(s[1], s[2]);
            final List<MEdge> initial = initialEdges(e0, 42L);
            // Warm both paths once, then report the best of 3 for each impl.
            runOnce(new NativeEdgeOps(), initial, triangles);
            runOnce(new IndexedEdgeOps(), initial, triangles);
            long[] bestNative = null;
            long[] bestIndexed = null;
            int dupes = 0;
            for (int round = 0; round < 3; round++) {
                final long[] n = runOnce(new NativeEdgeOps(), initial, triangles);
                final IndexedEdgeOps indexed = new IndexedEdgeOps();
                final long[] i = runOnce(indexed, initial, triangles);
                dupes = indexed.initialDuplicateKeys;
                if (bestNative == null || n[0] < bestNative[0]) bestNative = n;
                if (bestIndexed == null || i[0] < bestIndexed[0]) bestIndexed = i;
            }
            System.out.println("E0=" + e0 + " T=" + triangles.length
                + " nativeNs=" + bestNative[0] + " indexedNs=" + bestIndexed[0]
                + " nativeCmp=" + bestNative[1] + " indexedCmp=" + bestIndexed[1]
                + " idxDupes=" + dupes);
        }
        System.out.println("EDGE_INDEX_BENCH PASS tier=synthetic-only "
            + "indexBuildIncluded=true hostExtrapolation=false");
    }
}
