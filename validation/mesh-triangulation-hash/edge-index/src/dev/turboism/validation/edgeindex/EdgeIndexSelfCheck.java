package dev.turboism.validation.edgeindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sequence-level equivalence harness for the batch-local edge index candidate.
 *
 * <p>Equivalence is per operation, not per final state: after every primitive
 * op — including every single edge insertion inside an apply batch — the
 * comparator checks the return value (or thrown exception class+message), the
 * complete ordered edge list (endpoints + type), the version counter, and the
 * event log (degenerate-edge messages, retype events, progress markers).</p>
 *
 * <p>Exception comparison uses the real class name and the real message; the
 * only modeled fixture is the cancel exception ({@code ModeledCancel}, standing
 * in for {@code jp.noids.framework.e.a} at the verified {@code j/a.d()} sites)
 * and the Kotlin null-parameter message, whose parameter-name suffix is
 * implementation detail captured verbatim in {@link EdgeOps#nullTypeException}.</p>
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
        /** Arm progress() to throw ModeledCancel after n successful calls. */
        record ArmCancel(int progressCalls) implements Op {}
        /** A raw progress.d() site outside apply. */
        record Progress() implements Op {}
    }

    private record Outcome(boolean threw, String detail) {
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Throwable;
    }

    private static void fail(final String message) {
        throw new IllegalStateException(message);
    }

    private static Outcome attempt(final ThrowingCall call) {
        try {
            call.run();
            return new Outcome(false, "ok");
        } catch (final Throwable t) {
            return new Outcome(true, t.getClass().getName() + "|" + t.getMessage());
        }
    }

    /** Full state comparison after a micro-step; always runs, even post-throw. */
    private static void checkState(final String name, final String where,
                                   final EdgeOps a, final EdgeOps b) {
        if (!a.state().equals(b.state())) {
            fail(name + " " + where + " edge list diverges"
                 + "\n native=" + a.state() + "\n indexed=" + b.state());
        }
        if (a.version != b.version) {
            fail(name + " " + where + " version diverges: "
                 + a.version + " vs " + b.version);
        }
        if (!a.events.equals(b.events)) {
            fail(name + " " + where + " event stream diverges"
                 + "\n native=" + a.events + "\n indexed=" + b.events);
        }
        opsCompared++;
    }

    /**
     * Run one micro-call on each impl, compare outcomes and post-state.
     * Returns the shared outcome; callers abort the batch when it threw.
     */
    private static Outcome pair(final String name, final int step, final String label,
                                final ThrowingCall ca, final ThrowingCall cb,
                                final EdgeOps a, final EdgeOps b) {
        final Outcome oa = attempt(ca);
        final Outcome ob = attempt(cb);
        if (!oa.equals(ob)) {
            fail(name + " step " + step + " " + label + " outcome diverges: "
                 + "native=" + oa + " indexed=" + ob);
        }
        checkState(name, "step " + step + " " + label, a, b);
        return oa;
    }

    /**
     * Lock-step apply: both impls are driven through the same bytecode-ordered
     * sequence — progress, clear, batch-open, per-edge adds (comparing state
     * after every single insertion), batch-close in finally, post progress.
     * A throw at any site aborts the rest exactly once for both impls.
     */
    private static void applyLockStep(final String name, final int step,
                                      final NativeEdgeOps a, final IndexedEdgeOps b,
                                      final int[][] tris) {
        // j/a.d() at offset 190 — before clearAutoTriangulation; cancel aborts apply here.
        if (pair(name, step, "progress-pre", () -> a.progress(), () -> b.progress(), a, b)
                .threw()) {
            return;
        }
        if (pair(name, step, "clear", a::clearAutoTriangulation,
                 b::clearAutoTriangulation, a, b).threw()) {
            return;
        }
        // Batch boundary is candidate-internal lifecycle; a throw is a test bug.
        final Outcome ba = attempt(a::beginBatch);
        final Outcome bb = attempt(b::beginBatch);
        if (ba.threw() || bb.threw()) {
            fail(name + " step " + step + " beginBatch threw: " + ba + " / " + bb);
        }
        boolean aborted = false;
        String abortDetail = null;
        try {
            outer:
            for (final int[] tri : tris) {
                for (int slot = 0; slot < 3; slot++) {
                    final int s = slot;
                    final int[] row = tri;
                    // A thrown edge call aborts the rest of the apply loop —
                    // the exception propagates out of b.a natively.
                    final Outcome shared = pair(name, step, "edge[" + s + "]",
                        () -> ApplyRunner.edge(a, row, s),
                        () -> ApplyRunner.edge(b, row, s), a, b);
                    if (shared.threw()) {
                        aborted = true;
                        abortDetail = shared.detail();
                        break outer;
                    }
                }
            }
        } catch (final IllegalStateException divergence) {
            throw divergence; // real divergence — propagate as failure
        } catch (final RuntimeException driverLevel) {
            // A throw escaping the driver loop (e.g. arraylength on a null
            // triangles array) hits the shared driver once; both impls observed
            // the same boundary by construction, and the state comparison below
            // still proves it.
            aborted = true;
            abortDetail = driverLevel.getClass().getName()
                + "|" + driverLevel.getMessage();
        } finally {
            final Outcome ea = attempt(a::endBatch);
            final Outcome eb = attempt(b::endBatch);
            if (ea.threw() || eb.threw()) {
                fail(name + " step " + step + " endBatch threw: " + ea + " / " + eb);
            }
        }
        checkState(name, "step " + step + " post-loop", a, b);
        if (aborted) {
            events(a).add("applyAbort:" + abortDetail);
            events(b).add("applyAbort:" + abortDetail);
            checkState(name, "step " + step + " abort-events", a, b);
            return; // progress-post is unreachable in the native flow after an abort
        }
        // j/a.d() at offset 312 — after the loop.
        pair(name, step, "progress-post", () -> a.progress(), () -> b.progress(), a, b);
    }

    private static List<String> events(final EdgeOps ops) {
        return ops.events;
    }

    /** Replay one script on both impls, comparing state after every operation. */
    private static void replay(final String name, final List<Op> script,
                               final List<MEdge> initial) {
        final NativeEdgeOps a = new NativeEdgeOps();
        final IndexedEdgeOps b = new IndexedEdgeOps();
        a.edges.addAll(initial);
        b.edges.addAll(initial);
        int step = 0;
        for (final Op op : script) {
            step++;
            if (op instanceof Op.Apply apply) {
                applyLockStep(name, step, a, b, apply.triangles());
            } else if (op instanceof Op.Add add) {
                pair(name, step, "add",
                     () -> a.addEdgeIfNotExists(add.i1(), add.i2(), add.type()),
                     () -> b.addEdgeIfNotExists(add.i1(), add.i2(), add.type()), a, b);
            } else if (op instanceof Op.Clear) {
                pair(name, step, "clear", a::clearAutoTriangulation,
                     b::clearAutoTriangulation, a, b);
            } else if (op instanceof Op.ArmCancel arm) {
                a.armCancelAfter(arm.progressCalls());
                b.armCancelAfter(arm.progressCalls());
            } else if (op instanceof Op.Progress) {
                pair(name, step, "progress-raw", () -> a.progress(),
                     () -> b.progress(), a, b);
            } else {
                fail("unknown op " + op);
            }
        }
        cases++;
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

        // Regression for the index-invalidation counterexample caught at
        // random-seed-1: after Clear compacts the list, a stale index would
        // resolve (5,6) to its pre-clear position.
        replay("clear-invalidates-index-regression", List.of(
            new Op.Add(8, 9, EdgeType.AUTO_TRIANGULATION),
            new Op.Clear(),
            new Op.Add(5, 6, EdgeType.AUTO_TRIANGULATION),
            new Op.Add(2, 3, EdgeType.NORMAL)),
            List.of(e(0, 1, EdgeType.AUTO_TRIANGULATION),
                    e(5, 6, EdgeType.NORMAL)));

        replay("apply-batch-shared-edges", List.of(
            new Op.Apply(new int[][] {
                {0, 1, 2}, {1, 3, 2}, {2, 3, 4}, {3, 5, 4}}),
            new Op.Apply(new int[][] {{5, 4, 6}, {4, 7, 6}})),
            List.of(e(1, 2, EdgeType.NORMAL), e(9, 9, EdgeType.LOCKED)));

        replay("apply-with-degenerate-triangle", List.of(
            new Op.Apply(new int[][] {{0, 1, 1}, {1, 1, 2}, {3, 4, 5}})), List.of());

        // Malformed batch inputs; per bytecode, endpoint reads precede each call.
        replay("apply-null-array", List.of(
            new Op.Apply(null),
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION)), List.of());
        replay("apply-null-row", List.of(
            new Op.Apply(new int[][] {null, {1, 2, 3}})), List.of());
        replay("apply-empty-row", List.of(
            new Op.Apply(new int[][] {new int[0]})), List.of());
        replay("apply-short-row-1", List.of(
            new Op.Apply(new int[][] {{7}})), List.of());
        replay("apply-short-row-2", List.of(
            new Op.Apply(new int[][] {{7, 8}}), // edge (7,8) added, then AIOOBE
            new Op.Add(9, 9, EdgeType.AUTO_TRIANGULATION)), List.of());
        replay("apply-long-row-ignored-extras", List.of(
            new Op.Apply(new int[][] {{1, 2, 3, 99, 100}})), List.of());

        // Deterministic cancel injection at the modeled d() sites.
        replay("cancel-before-loop", List.of(
            new Op.ArmCancel(0),
            new Op.Apply(new int[][] {{0, 1, 2}, {1, 3, 2}}),
            new Op.Add(4, 5, EdgeType.AUTO_TRIANGULATION)),
            List.of(e(0, 1, EdgeType.AUTO_TRIANGULATION), e(2, 3, EdgeType.NORMAL)));
        replay("cancel-after-loop", List.of(
            new Op.ArmCancel(1),
            new Op.Apply(new int[][] {{0, 1, 2}}),
            new Op.Add(4, 5, EdgeType.AUTO_TRIANGULATION),
            new Op.Clear(),
            new Op.Add(0, 1, EdgeType.AUTO_TRIANGULATION)), List.of());
        replay("cancel-raw-progress", List.of(
            new Op.ArmCancel(0),
            new Op.Progress(),
            new Op.Progress(),
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION)), List.of());
        replay("cancel-then-new-apply", List.of(
            new Op.ArmCancel(0),
            new Op.Apply(new int[][] {{0, 1, 2}}),
            new Op.Apply(new int[][] {{3, 4, 5}, {5, 6, 3}})), List.of());
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
            if (pick < 74) {
                script.add(new Op.Add(rng.nextInt(vertexSpace), rng.nextInt(vertexSpace),
                                      EdgeType.values()[rng.nextInt(5)]));
            } else if (pick < 82) {
                final int v = rng.nextInt(vertexSpace);
                script.add(new Op.Add(v, v, EdgeType.values()[rng.nextInt(5)]));
            } else if (pick < 87) {
                script.add(new Op.Clear());
            } else if (pick < 90) {
                script.add(new Op.ArmCancel(rng.nextInt(3)));
                script.add(new Op.Progress());
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
