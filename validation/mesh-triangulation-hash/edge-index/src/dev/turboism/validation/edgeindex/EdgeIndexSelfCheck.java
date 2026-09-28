package dev.turboism.validation.edgeindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sequence-level equivalence harness for the batch-local edge index candidate.
 *
 * <p>Equivalence is per operation, not per final state: after every primitive
 * op the comparator checks the return value (or thrown exception class+prefix),
 * the complete ordered edge list (endpoints + type), the version counter, and
 * the event log (degenerate-edge messages, retype events, progress markers).</p>
 *
 * <p>Evidence tier: simulation-level only. The reference is a hand transcription
 * of the official 5303 bytecode; official classes are never loaded or executed.
 * A divergence is printed as a minimal counterexample and stops the run — the
 * candidate must not be carried forward on weakened criteria.</p>
 */
public final class EdgeIndexSelfCheck {
    private static int cases;
    private static int opsCompared;

    private EdgeIndexSelfCheck() {
    }

    /** Primitive operation replayed identically on both implementations. */
    private sealed interface Op {
        record Add(int i1, int i2, EdgeType type) implements Op {}
        record Clear() implements Op {}
        record Apply(int[][] triangles) implements Op {}
    }

    private static void fail(final String message) {
        throw new IllegalStateException(message);
    }

    /** Replay one script on both impls, comparing state after every operation. */
    private static void replay(final String name, final List<Op> script,
                               final List<MEdge> initial) {
        final NativeEdgeOps nativeOps = new NativeEdgeOps();
        final IndexedEdgeOps indexedOps = new IndexedEdgeOps();
        nativeOps.edges.addAll(initial);
        indexedOps.edges.addAll(initial);
        int step = 0;
        for (final Op op : script) {
            step++;
            final Outcome a = invoke(nativeOps, op);
            final Outcome b = invoke(indexedOps, op);
            if (!a.equals(b)) {
                fail(name + " step " + step + " outcome diverges: " + op
                     + " native=" + a + " indexed=" + b);
            }
            if (!nativeOps.state().equals(indexedOps.state())) {
                fail(name + " step " + step + " edge list diverges after " + op
                     + "\n native=" + nativeOps.state()
                     + "\n indexed=" + indexedOps.state());
            }
            if (nativeOps.version != indexedOps.version) {
                fail(name + " step " + step + " version diverges after " + op
                     + ": " + nativeOps.version + " vs " + indexedOps.version);
            }
            if (!nativeOps.events.equals(indexedOps.events)) {
                fail(name + " step " + step + " event stream diverges after " + op
                     + "\n native=" + nativeOps.events
                     + "\n indexed=" + indexedOps.events);
            }
            opsCompared++;
        }
        cases++;
    }

    private record Outcome(boolean threw, String detail) {
    }

    private static Outcome invoke(final EdgeOps ops, final Op op) {
        try {
            if (op instanceof Op.Add add) {
                return new Outcome(false,
                    "ret=" + ops.addEdgeIfNotExists(add.i1(), add.i2(), add.type()));
            } else if (op instanceof Op.Clear) {
                ops.clearAutoTriangulation();
                return new Outcome(false, "cleared");
            } else {
                final Op.Apply apply = (Op.Apply) op;
                ApplyRunner.applyTriangles(ops, apply.triangles());
                return new Outcome(false, "applied");
            }
        } catch (final Throwable t) {
            // Compare exception class and the Kotlin message prefix, not the
            // (implementation-detail) parameter name suffix.
            final String prefix = t instanceof NullPointerException
                ? "Parameter specified as non-null is null" : String.valueOf(t.getMessage());
            return new Outcome(true, t.getClass().getName() + ":" + prefix);
        }
    }

    private static MEdge e(final int i1, final int i2, final EdgeType t) {
        return new MEdge(i1, i2, t);
    }

    private static void directed() {
        replay("empty-then-hit", List.of(
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(2, 1, EdgeType.AUTO_TRIANGULATION)), List.of());

        replay("initial-duplicate-endpoints", List.of(
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION)),
            List.of(e(1, 2, EdgeType.NORMAL), e(1, 2, EdgeType.LOCKED),
                    e(1, 2, EdgeType.USER_TRIANGULATION)));

        replay("retype-not-initialized", List.of(
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(1, 2, EdgeType.NORMAL)),
            List.of(e(1, 2, EdgeType.NOT_INITIALIZED)));

        replay("no-retype-higher-priority", List.of(
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION)),
            List.of(e(1, 2, EdgeType.LOCKED), e(1, 2, EdgeType.NORMAL),
                    e(3, 4, EdgeType.USER_TRIANGULATION)));

        replay("degenerate-then-normal", List.of(
            new Op.Add(5, 5, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(0, 0, EdgeType.NORMAL),
            new Op.Add(5, 6, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(6, 5, EdgeType.AUTO_TRIANGULATION)), List.of());

        replay("null-type-npe", List.of(
            new Op.Add(1, 2, null),
            new Op.Add(3, 4, EdgeType.AUTO_TRIANGULATION)), List.of());

        replay("extreme-indices", List.of(
            new Op.Add(Integer.MIN_VALUE, Integer.MAX_VALUE, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(Integer.MAX_VALUE, Integer.MIN_VALUE, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(-1, 0, EdgeType.NORMAL),
            new Op.Add(0, -1, EdgeType.LOCKED)), List.of());

        replay("clear-removes-only-auto", List.of(
            new Op.Clear(),
            new Op.Add(7, 8, EdgeType.AUTO_TRIANGULATION),
            new Op.Clear()),
            List.of(e(1, 2, EdgeType.AUTO_TRIANGULATION), e(2, 3, EdgeType.NORMAL),
                    e(4, 5, EdgeType.USER_TRIANGULATION),
                    e(6, 7, EdgeType.AUTO_TRIANGULATION)));

        replay("apply-batch-shared-edges", List.of(
            new Op.Apply(new int[][] {
                {0, 1, 2}, {1, 3, 2}, {2, 3, 4}, {3, 5, 4}}),
            new Op.Apply(new int[][] {{5, 4, 6}, {4, 7, 6}})),
            List.of(e(1, 2, EdgeType.NORMAL), e(9, 9, EdgeType.LOCKED)));

        replay("apply-with-degenerate-triangle", List.of(
            new Op.Apply(new int[][] {{0, 1, 1}, {1, 1, 2}, {3, 4, 5}})), List.of());
    }

    /** Fixed-seed random op streams: adds, degenerates, clears, apply batches. */
    private static void randomized(final long seed) {
        final Random rng = new Random(seed);
        final List<Op> script = new ArrayList<>();
        final List<MEdge> initial = new ArrayList<>();
        final int vertexSpace = 200;
        for (int i = 0; i < 40; i++) {
            final EdgeType[] types = EdgeType.values();
            initial.add(e(rng.nextInt(vertexSpace), rng.nextInt(vertexSpace),
                          types[rng.nextInt(types.length)]));
        }
        for (int i = 0; i < 4000; i++) {
            final int pick = rng.nextInt(100);
            if (pick < 78) {
                script.add(new Op.Add(rng.nextInt(vertexSpace), rng.nextInt(vertexSpace),
                                      EdgeType.values()[rng.nextInt(5)]));
            } else if (pick < 86) {
                final int v = rng.nextInt(vertexSpace);
                script.add(new Op.Add(v, v, EdgeType.values()[rng.nextInt(5)]));
            } else if (pick < 91) {
                script.add(new Op.Clear());
            } else {
                final int t = 4 + rng.nextInt(24);
                final int[][] tris = new int[t][];
                for (int k = 0; k < t; k++) {
                    final int a = rng.nextInt(vertexSpace);
                    tris[k] = new int[] {a, a + 1 + rng.nextInt(3),
                                         a + 2 + rng.nextInt(4)};
                }
                script.add(new Op.Apply(tris));
            }
        }
        replay("random-seed-" + seed, script, initial);
    }

    public static void main(final String[] args) {
        directed();
        for (final long seed : new long[] {1L, 7L, 42L, 199L, 2026L}) {
            randomized(seed);
        }
        System.out.println("EDGE_INDEX_SELFCHECK PASS cases=" + cases
            + " opsCompared=" + opsCompared
            + " tier=simulation-no-official-execution");
    }
}
