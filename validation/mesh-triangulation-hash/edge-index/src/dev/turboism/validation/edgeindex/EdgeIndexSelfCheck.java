package dev.turboism.validation.edgeindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sequence-level equivalence harness for the batch-local edge index candidate.
 *
 * <p>Equivalence is per operation, not per final state: after every primitive
 * op — including every single edge insertion inside an apply batch — the
 * comparator checks the <b>returned index</b> (or thrown exception
 * class+message), the complete ordered edge list (endpoints + type), the
 * version counter, and the event log (degenerate-edge messages, retype events,
 * progress markers).</p>
 *
 * <p>Exception comparison uses the real class name and the real message; the
 * only modeled fixture is the cancel exception ({@code ModeledCancel}, standing
 * in for {@code jp.noids.framework.e.a} at the verified {@code j/a.d()} sites)
 * and the Kotlin null-parameter message captured verbatim in
 * {@link EdgeOps#nullTypeException}.</p>
 *
 * <p>Two drive modes: well-formed batches run lock-step so state is compared
 * after every edge; malformed/cancel cases additionally drive each impl through
 * {@code ApplyRunner.applyTriangles} independently with pinned expectations —
 * no events are ever written into both streams to manufacture equality.</p>
 *
 * <p>Evidence tier: simulation-level only. The reference is a hand transcription
 * of the official 5303 bytecode; official classes are never loaded or executed.
 * A divergence is printed as a minimal counterexample and stops the run — the
 * candidate must not be carried forward on weakened criteria.</p>
 */
public final class EdgeIndexSelfCheck {
    private static final Object UNIT = new Object() {
        @Override public String toString() {
            return "unit";
        }
    };
    private static int cases;
    private static int opsCompared;

    private EdgeIndexSelfCheck() {
    }

    /** Primitive operation replayed identically on both implementations. */
    private sealed interface Op {
        record Add(int i1, int i2, EdgeType type) implements Op {}
        record Clear() implements Op {}
        /** Well-formed batch: lock-step, per-edge comparison. */
        record Apply(int[][] triangles) implements Op {}
        /**
         * Malformed/cancel batch: each impl is driven through
         * ApplyRunner.applyTriangles independently; the outcome and the pinned
         * expectations (state/version/progress count/throw prefix) are then
         * checked against both.
         */
        record ApplyIso(int[][] triangles, String expectThrowPrefix,
                        List<String> expectState, int expectVersion,
                        long expectProgressEvents) implements Op {}
        /** Arm progress() to throw ModeledCancel after n successful calls. */
        record ArmCancel(int progressCalls) implements Op {}
        /** A raw progress.d() site outside apply. */
        record Progress() implements Op {}
    }

    private record Outcome(boolean threw, String detail, String result) {
    }

    @FunctionalInterface
    private interface ThrowingCall {
        Object run() throws Throwable;
    }

    private static void fail(final String message) {
        throw new IllegalStateException(message);
    }

    private static Outcome attempt(final ThrowingCall call) {
        try {
            final Object result = call.run();
            return new Outcome(false, "ok",
                result == null ? "null" : String.valueOf(result));
        } catch (final Throwable t) {
            return new Outcome(true, t.getClass().getName() + "|" + t.getMessage(),
                null);
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
     * Run one micro-call on each impl, compare outcomes (threw, exception
     * detail, returned value) and post-state. Returns the shared outcome;
     * callers abort the batch when it threw.
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
     * sequence — progress, clear, batch-open, per-edge adds (comparing the
     * returned index and state after every single insertion), batch-close in
     * finally, post progress. A throw at any site aborts the rest exactly once
     * for both impls.
     */
    private static void applyLockStep(final String name, final int step,
                                      final NativeEdgeOps a, final IndexedEdgeOps b,
                                      final int[][] tris) {
        // j/a.d() at offset 190 — before clearAutoTriangulation; cancel aborts apply here.
        if (pair(name, step, "progress-pre", () -> {
            a.progress();
            return UNIT;
        }, () -> {
            b.progress();
            return UNIT;
        }, a, b).threw()) {
            return;
        }
        if (pair(name, step, "clear", () -> {
            a.clearAutoTriangulation();
            return UNIT;
        }, () -> {
            b.clearAutoTriangulation();
            return UNIT;
        }, a, b).threw()) {
            return;
        }
        // Batch boundary is candidate-internal lifecycle; a throw is a test bug.
        final Outcome ba = attempt(() -> {
            a.beginBatch();
            return UNIT;
        });
        final Outcome bb = attempt(() -> {
            b.beginBatch();
            return UNIT;
        });
        if (ba.threw() || bb.threw()) {
            fail(name + " step " + step + " beginBatch threw: " + ba + " / " + bb);
        }
        boolean aborted = false;
        try {
            outer:
            for (final int[] tri : tris) {
                for (int slot = 0; slot < 3; slot++) {
                    final int s = slot;
                    final int[] row = tri;
                    // A thrown edge call aborts the rest of the apply loop —
                    // the exception propagates out of b.a natively. The returned
                    // index is compared per edge even though the host pops it.
                    final Outcome shared = pair(name, step, "edge[" + s + "]",
                        () -> ApplyRunner.edge(a, row, s),
                        () -> ApplyRunner.edge(b, row, s), a, b);
                    if (shared.threw()) {
                        aborted = true;
                        break outer;
                    }
                }
            }
        } catch (final IllegalStateException divergence) {
            throw divergence; // real divergence — propagate as failure
        } catch (final RuntimeException driverLevel) {
            // A throw escaping the shared driver loop (e.g. arraylength on a
            // null triangles array) is observed identically by construction;
            // the post-loop state comparison still proves both sides agree.
            aborted = true;
        } finally {
            final Outcome ea = attempt(() -> {
                a.endBatch();
                return UNIT;
            });
            final Outcome eb = attempt(() -> {
                b.endBatch();
                return UNIT;
            });
            if (ea.threw() || eb.threw()) {
                fail(name + " step " + step + " endBatch threw: " + ea + " / " + eb);
            }
        }
        checkState(name, "step " + step + " post-loop", a, b);
        if (a.batchIndexActive() || b.batchIndexActive()) {
            fail(name + " step " + step + " batch index leaked past endBatch");
        }
        if (aborted) {
            return; // progress-post is unreachable in the native flow after an abort
        }
        // j/a.d() at offset 312 — after the loop.
        pair(name, step, "progress-post", () -> {
            a.progress();
            return UNIT;
        }, () -> {
            b.progress();
            return UNIT;
        }, a, b);
    }

    /**
     * Independent-instance drive for malformed/cancel cases: run the real
     * single-impl {@code ApplyRunner.applyTriangles} on each side separately,
     * capture the actual thrown type+message, then compare the pair AND the
     * pinned expectations (state, version, progress-event count, index leak).
     */
    private static void applyIsolated(final String name, final int step,
                                      final NativeEdgeOps a, final IndexedEdgeOps b,
                                      final Op.ApplyIso iso) {
        final Outcome oa = attempt(() -> {
            ApplyRunner.applyTriangles(a, iso.triangles());
            return UNIT;
        });
        final Outcome ob = attempt(() -> {
            ApplyRunner.applyTriangles(b, iso.triangles());
            return UNIT;
        });
        if (!oa.equals(ob)) {
            fail(name + " step " + step + " apply outcome diverges: "
                 + "native=" + oa + " indexed=" + ob);
        }
        checkState(name, "step " + step + " isolated-apply", a, b);
        if (iso.expectThrowPrefix() == null) {
            if (oa.threw()) {
                fail(name + " step " + step + " unexpectedly threw: " + oa);
            }
        } else if (!(oa.threw() && oa.detail().startsWith(iso.expectThrowPrefix()))) {
            fail(name + " step " + step + " expected throw prefix '"
                 + iso.expectThrowPrefix() + "' but got " + oa);
        }
        if (!a.state().equals(iso.expectState())) {
            fail(name + " step " + step + " state diverges from pinned expectation"
                 + "\n expected=" + iso.expectState() + "\n actual=" + a.state());
        }
        if (a.version != iso.expectVersion()) {
            fail(name + " step " + step + " version " + a.version
                 + " != expected " + iso.expectVersion());
        }
        final long progressEvents =
            a.events.stream().filter(e -> e.equals("progress.d")).count();
        if (progressEvents != iso.expectProgressEvents()) {
            fail(name + " step " + step + " progress events " + progressEvents
                 + " != expected " + iso.expectProgressEvents());
        }
        if (a.batchIndexActive() || b.batchIndexActive()) {
            fail(name + " step " + step + " batch index leaked past apply abort");
        }
    }

    /** Tampered candidate: identical semantics, deliberately wrong return index. */
    private static final class TamperedReturnEdgeOps extends IndexedEdgeOps {
        @Override
        int addEdgeIfNotExists(final int i1, final int i2, final EdgeType type) {
            final int r = super.addEdgeIfNotExists(i1, i2, type);
            return r < 0 ? r : r + 1;
        }
    }

    /**
     * Negative control: a candidate that mutates nothing but reports a wrong
     * index must be rejected by the comparator at the exact step.
     */
    private static void expectReturnIndexRejection() {
        final NativeEdgeOps a = new NativeEdgeOps();
        final TamperedReturnEdgeOps tampered = new TamperedReturnEdgeOps();
        tampered.edges.add(e(9, 9, EdgeType.LOCKED)); // inert pre-existing edge
        a.edges.add(e(9, 9, EdgeType.LOCKED));
        try {
            pair("neg-return-index", 0, "add",
                 () -> a.addEdgeIfNotExists(1, 2, EdgeType.AUTO_TRIANGULATION),
                 () -> tampered.addEdgeIfNotExists(1, 2, EdgeType.AUTO_TRIANGULATION),
                 a, tampered);
        } catch (final IllegalStateException expected) {
            if (!expected.getMessage().contains("outcome diverges")) {
                fail("negative control fired for the wrong reason: " + expected);
            }
            System.out.println("EDGE_INDEX_NEGATIVE_CONTROL PASS "
                + "returnIndexMismatchRejected=true");
            return;
        }
        fail("negative control: tampered return index was NOT rejected");
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
            } else if (op instanceof Op.ApplyIso iso) {
                applyIsolated(name, step, a, b, iso);
            } else if (op instanceof Op.Add add) {
                pair(name, step, "add",
                     () -> a.addEdgeIfNotExists(add.i1(), add.i2(), add.type()),
                     () -> b.addEdgeIfNotExists(add.i1(), add.i2(), add.type()), a, b);
            } else if (op instanceof Op.Clear) {
                pair(name, step, "clear", () -> {
                    a.clearAutoTriangulation();
                    return UNIT;
                }, () -> {
                    b.clearAutoTriangulation();
                    return UNIT;
                }, a, b);
            } else if (op instanceof Op.ArmCancel arm) {
                a.armCancelAfter(arm.progressCalls());
                b.armCancelAfter(arm.progressCalls());
            } else if (op instanceof Op.Progress) {
                pair(name, step, "progress-raw", () -> {
                    a.progress();
                    return UNIT;
                }, () -> {
                    b.progress();
                    return UNIT;
                }, a, b);
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

        // Malformed inputs via independent ApplyRunner drives + pinned state.
        // progress-pre fires first, then clear strips AUTO edges, then the loop.
        replay("apply-iso-null-array", List.of(
            new Op.ApplyIso(null, "java.lang.NullPointerException",
                List.of(), 0, 1)), List.of());

        replay("apply-iso-null-row", List.of(
            new Op.ApplyIso(new int[][] {null}, "java.lang.NullPointerException",
                List.of("5,6,NORMAL"), 0, 1)),
            List.of(e(0, 1, EdgeType.AUTO_TRIANGULATION),
                    e(5, 6, EdgeType.NORMAL)));

        replay("apply-iso-empty-row", List.of(
            new Op.ApplyIso(new int[][] {new int[0]},
                "java.lang.ArrayIndexOutOfBoundsException",
                List.of(), 0, 1)), List.of());

        replay("apply-iso-short-row-1", List.of(
            new Op.ApplyIso(new int[][] {{7}},
                "java.lang.ArrayIndexOutOfBoundsException",
                List.of(), 0, 1)), List.of());

        // Length-2 row: edge (7,8) is inserted, then the tri[2] read throws.
        replay("apply-iso-short-row-2", List.of(
            new Op.ApplyIso(new int[][] {{7, 8}},
                "java.lang.ArrayIndexOutOfBoundsException",
                List.of("7,8,AUTO_TRIANGULATION"), 1, 1),
            new Op.Add(9, 9, EdgeType.AUTO_TRIANGULATION)), List.of());

        replay("apply-iso-long-row-extra-ignored", List.of(
            new Op.ApplyIso(new int[][] {{1, 2, 3, 99, 100}}, null,
                List.of("1,2,AUTO_TRIANGULATION", "2,3,AUTO_TRIANGULATION",
                        "1,3,AUTO_TRIANGULATION"),
                3, 2)), List.of());

        // Deterministic cancel injection at the modeled d() sites.
        // Armed 0: progress-pre throws before clear — AUTO edges remain.
        replay("cancel-before-loop", List.of(
            new Op.ArmCancel(0),
            new Op.ApplyIso(new int[][] {{0, 1, 2}, {1, 3, 2}},
                "dev.turboism.validation.edgeindex.EdgeOps$ModeledCancel",
                List.of("0,1,AUTO_TRIANGULATION", "2,3,NORMAL"), 0, 1),
            new Op.Add(4, 5, EdgeType.AUTO_TRIANGULATION)),
            List.of(e(0, 1, EdgeType.AUTO_TRIANGULATION),
                    e(2, 3, EdgeType.NORMAL)));

        // Armed 1: pre progress OK, loop runs, post-loop progress throws.
        replay("cancel-after-loop", List.of(
            new Op.ArmCancel(1),
            new Op.ApplyIso(new int[][] {{0, 1, 2}},
                "dev.turboism.validation.edgeindex.EdgeOps$ModeledCancel",
                List.of("0,1,AUTO_TRIANGULATION", "1,2,AUTO_TRIANGULATION",
                        "0,2,AUTO_TRIANGULATION"),
                3, 2),
            new Op.Add(4, 5, EdgeType.AUTO_TRIANGULATION),
            new Op.Clear(),
            new Op.Add(0, 1, EdgeType.AUTO_TRIANGULATION)), List.of());

        replay("cancel-raw-progress", List.of(
            new Op.ArmCancel(0),
            new Op.Progress(),
            new Op.Progress(),
            new Op.Add(1, 2, EdgeType.AUTO_TRIANGULATION)), List.of());

        // Cancel aborts the first apply; re-arm beyond this test's progress
        // calls and verify that a fresh batch completes with its expected state.
        replay("cancel-then-new-apply", List.of(
            new Op.ArmCancel(0),
            new Op.ApplyIso(new int[][] {{0, 1, 2}},
                "dev.turboism.validation.edgeindex.EdgeOps$ModeledCancel",
                List.of(), 0, 1),
            new Op.ArmCancel(Integer.MAX_VALUE),
            new Op.ApplyIso(new int[][] {{3, 4, 5}, {5, 6, 3}}, null,
                List.of("3,4,AUTO_TRIANGULATION", "4,5,AUTO_TRIANGULATION",
                        "3,5,AUTO_TRIANGULATION", "5,6,AUTO_TRIANGULATION",
                        "3,6,AUTO_TRIANGULATION"),
                6, 3)), List.of());
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
        expectReturnIndexRejection();
        System.out.println("EDGE_INDEX_SELFCHECK PASS cases=" + cases
            + " opsCompared=" + opsCompared
            + " tier=simulation-no-official-execution");
    }
}
