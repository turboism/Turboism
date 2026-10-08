package dev.turboism.validation.edgeindex;

import dev.turboism.validation.edgeindex.EdgeOps.ModeledCancel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Synthetic cost comparison for the batch-local edge index candidate.
 *
 * <p>Reports per-impl counters with distinct units — {@code endpointComparisons}
 * (native linear first-hit scans) vs {@code indexBuildEntries}/{@code indexLookups}
 * (candidate index construction + hash probes). They are never summed or divided
 * into a shared "comparison count". Wall time is sampled with interleaved
 * A/B ordering (native/indexed alternate first) and every sample is reported;
 * no threshold gates a PASS — the equivalence gate lives in the self-check.</p>
 *
 * <p>Results are JVM-simulation figures only: they must not be extrapolated to
 * host cost, they cannot decompose the earlier 50s apply observation (the real
 * {@code a.a(progress)} triangulation compute is not part of this slice), and
 * sizes are synthetic — not taken from any measured mesh.</p>
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

    private static long time(final EdgeOps ops, final List<MEdge> initial,
                             final int[][] triangles) {
        ops.edges.addAll(initial);
        final long start = System.nanoTime();
        try {
            ApplyRunner.applyTriangles(ops, triangles);
        } catch (final ModeledCancel e) {
            throw new IllegalStateException(e);
        }
        return System.nanoTime() - start;
    }

    public static void main(final String[] args) {
        // name, initial edge count, triangles
        record Case(String name, int e0, int[][] triangles) {
        }
        final List<Case> cases = List.of(
            new Case("empty-empty", 0, new int[0][]),
            new Case("empty-single", 0, new int[][] {{0, 1, 2}}),
            new Case("tiny-8x2", 8, new int[][] {{0, 1, 2}, {2, 3, 0}}),
            new Case("small-64-4x4", 64, gridTriangles(4, 4)),
            new Case("mid-256-24x24", 256, gridTriangles(24, 24)),
            new Case("large-1024-48x48", 1024, gridTriangles(48, 48)),
            new Case("large-2048-80x60", 2048, gridTriangles(80, 60)));
        final int rounds = 5; // interleaved A/B per round: N,I then I,N alternately
        for (final Case c : cases) {
            final List<MEdge> initial = initialEdges(c.e0(), 42L);
            final List<Long> nativeNs = new ArrayList<>();
            final List<Long> indexedNs = new ArrayList<>();
            long endpointComparisons = 0;
            long indexBuildEntries = 0;
            long indexLookups = 0;
            int dupes = 0;
            for (int r = 0; r < rounds; r++) {
                if (r % 2 == 0) {
                    final NativeEdgeOps n = new NativeEdgeOps();
                    nativeNs.add(time(n, initial, c.triangles()));
                    endpointComparisons = n.endpointComparisons;
                    final IndexedEdgeOps i = new IndexedEdgeOps();
                    indexedNs.add(time(i, initial, c.triangles()));
                    indexBuildEntries = i.indexBuildEntries;
                    indexLookups = i.indexLookups;
                    dupes = i.initialDuplicateKeys;
                } else {
                    final IndexedEdgeOps i = new IndexedEdgeOps();
                    indexedNs.add(time(i, initial, c.triangles()));
                    indexBuildEntries = i.indexBuildEntries;
                    indexLookups = i.indexLookups;
                    dupes = i.initialDuplicateKeys;
                    final NativeEdgeOps n = new NativeEdgeOps();
                    nativeNs.add(time(n, initial, c.triangles()));
                    endpointComparisons = n.endpointComparisons;
                }
            }
            System.out.println("case=" + c.name() + " E0=" + c.e0()
                + " T=" + c.triangles().length
                + " nativeNs=" + nativeNs
                + " indexedNs=" + indexedNs
                + " referenceEndpointComparisons=" + endpointComparisons
                + " indexBuildEntries=" + indexBuildEntries
                + " indexLookups=" + indexLookups
                + " idxDupes=" + dupes);
        }
        System.out.println("EDGE_INDEX_BENCH PASS tier=synthetic-only "
            + "indexBuildIncluded=true hostExtrapolation=false "
            + "decomposesOriginal50s=false");
    }
}
