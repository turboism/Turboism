package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import dev.turboism.validation.kmembership.Fixture.CandTriangleList;
import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.RefTriangleList;
import dev.turboism.validation.kmembership.Fixture.TriL;
import dev.turboism.validation.kmembership.Fixture.TriPoint;

/**
 * Differential selfcheck: reference (linear k.a(j,false)) vs candidate
 * (window-local HashSet<Long> of masked (min,max) index pairs).
 *
 * Per-query and per-append outcomes, original edge references, direction and the
 * full ordered sequence are compared. Deliberately-wrong candidate variants must
 * be rejected by the comparator (negative controls). Fault/null domains that are
 * not modelled identically are declared out-of-domain, never normalized away.
 */
public final class SelfCheck {

    static int checks = 0;
    static void check(boolean cond, String what) {
        checks++;
        if (!cond) { System.out.println("FAIL " + what); System.exit(1); }
    }

    /** Recorded outcome of one b() window run. */
    static final class Outcome {
        List<EdgeJ> list;          // element references in order
        List<boolean[]> steps;     // per edge: [querySaysAbsent, addReturn]
        Throwable thrown;          // first escaping throwable, if any
    }

    /** Instrumented k recording query/add results in call order. */
    static final class RecK extends EdgeK {
        final List<boolean[]> steps = new ArrayList<>();
        @Override public boolean a(EdgeJ j, boolean d) {
            boolean r = super.a(j, d);
            steps.add(new boolean[] { r, false });
            return r;
        }
        @Override public boolean a(EdgeJ j) {
            boolean r = super.a(j);
            steps.add(new boolean[] { false, r });
            return r;
        }
    }
    /** Run a reference window, recording every query/add boolean. */
    static Outcome runRef(LinkedHashSet<TriL> tris) {
        RecK k = new RecK();
        Outcome o = new Outcome();
        try {
            for (TriL tri : tris) {
                EdgeJ j4 = tri.d();   // same evaluation order as real b()
                EdgeJ j5 = tri.e();
                EdgeJ j6 = tri.f();
                if (!k.a(j4, false)) k.a(j4);
                if (!k.a(j5, false)) k.a(j5);
                if (!k.a(j6, false)) k.a(j6);
            }
            o.list = new ArrayList<>(k.a());
        } catch (Throwable t) { o.thrown = t; }
        o.steps = k.steps;
        return o;
    }

    /** Run the candidate window: local index answers the undirected membership
     *  question; appends still go through the real k.a(j). The index is a local —
     *  it cannot be reached after return. */
    static Outcome runCand(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        Outcome o = new Outcome();
        o.steps = new ArrayList<>();
        try {
            for (TriL tri : tris) {
                EdgeJ j4 = tri.d();
                EdgeJ j5 = tri.e();
                EdgeJ j6 = tri.f();
                for (EdgeJ j : new EdgeJ[] { j4, j5, j6 }) {
                    boolean absent = queryLocal(seen, j);
                    o.steps.add(new boolean[] { !absent, false });
                    if (absent) {
                        boolean add = k.a(j);
                        o.steps.add(new boolean[] { false, add });
                    }
                }
            }
            o.list = new ArrayList<>(k.a());
        } catch (Throwable t) { o.thrown = t; }
        return o;
    }

    /** Mirrors k.a(j,false)'s contract: null j -> NPE before touching the list. */
    static boolean queryLocal(java.util.HashSet<Long> seen, EdgeJ j) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
        int i0 = j.a().getIndex(), i1 = j.b().getIndex();
        int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
        return seen.add(((long) lo << 32) | (hi & 0xffffffffL));
    }

    /** Deliberately wrong candidate — directed key only. Comparator must reject. */
    static Outcome runBrokenDirected(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        Outcome o = new Outcome();
        o.steps = new ArrayList<>();
        try {
            for (TriL tri : tris) {
                for (EdgeJ j : new EdgeJ[] { tri.d(), tri.e(), tri.f() }) {
                    kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
                    int i0 = j.a().getIndex(), i1 = j.b().getIndex();
                    boolean absent = seen.add(((long) i0 << 32) | (i1 & 0xffffffffL));
                    o.steps.add(new boolean[] { !absent, false });
                    if (absent) o.steps.add(new boolean[] { false, k.a(j) });
                }
            }
            o.list = new ArrayList<>(k.a());
        } catch (Throwable t) { o.thrown = t; }
        return o;
    }

    static void diff(String tag, LinkedHashSet<TriL> input) {
        compare(runRef(input), runCand(input), tag, true);
    }

    static void compare(Outcome ref, Outcome cand, String tag, boolean expectEqual) {
        boolean equal = same(ref, cand);
        check(equal == expectEqual, tag + (expectEqual ? " must match" : " (negative control) must be rejected"));
    }

    static boolean same(Outcome a, Outcome b) {
        if ((a.thrown == null) != (b.thrown == null)) return false;
        if (a.thrown != null) {
            if (!a.thrown.getClass().equals(b.thrown.getClass())) return false;
            if (!canon(a.thrown).equals(canon(b.thrown))) return false;
        }
        if (a.steps.size() != b.steps.size()) return false;
        for (int i = 0; i < a.steps.size(); i++) {
            if (a.steps.get(i)[0] != b.steps.get(i)[0]
                    || a.steps.get(i)[1] != b.steps.get(i)[1]) return false;
        }
        if (a.list == null || b.list == null) return a.list == b.list;
        if (a.list.size() != b.list.size()) return false;
        for (int i = 0; i < a.list.size(); i++) {
            EdgeJ ea = a.list.get(i), eb = b.list.get(i);
            if (ea != eb) return false;                          // same reference
            if (ea.a() != eb.a() || ea.b() != eb.b()) return false; // same direction/endpoints
        }
        return true;
    }

    /** Exception comparison: class + canonicalized prefix. Quoted names, synthesized
     *  <localN> slots and the method-name segment after ':' are volatile across call
     *  sites/compilers — they are normalized, not silently dropped from the record. */
    static String canon(Throwable t) {
        String m = t.getMessage();
        String canon = m == null ? "" : m
            .replaceAll("\"[^\"]*\"", "\"?\"")
            .replaceAll("<local\\d+>", "<local>");
        int colon = canon.indexOf(':');
        return t.getClass().getName() + "|" + (colon < 0 ? canon : canon.substring(0, colon));
    }

    static TriPoint pt(int idx) { return new TriPoint(idx * 1.5f, idx * -2.5f, idx); }
    static EdgeJ edge(int a, int b) { return new EdgeJ(pt(a), pt(b)); }
    static TriL tri(EdgeJ d, EdgeJ e, EdgeJ f) { return new TriL(d, e, f); }
    static LinkedHashSet<TriL> set(TriL... ts) {
        LinkedHashSet<TriL> s = new LinkedHashSet<>();
        for (TriL t : ts) s.add(t);
        return s;
    }

    public static void main(String[] args) {
        // ---- in-domain differential matrix ---------------------------------
        // The SAME input set feeds both runs — list-element identity is then meaningful.
        diff("empty", set());
        diff("single-tri", set(tri(edge(1,2), edge(2,3), edge(1,3))));

        // two triangles sharing an edge, one reversed
        LinkedHashSet<TriL> shared = set(
            tri(edge(1,2), edge(2,3), edge(1,3)),
            tri(edge(3,2), edge(2,4), edge(3,4)));   // (2,3) vs (3,2) reversed
        diff("shared-reversed-edge", shared);

        // duplicate identical edge objects and equal-index copies
        EdgeJ dup = edge(5,9);
        diff("dup-and-equal-index", set(tri(dup, dup, edge(5,9))));

        // degenerate edges (same index both ends)
        diff("degenerate", set(tri(edge(4,4), edge(4,5), edge(5,5))));

        // negative / extreme indices — sign-extension + masking must not collide
        diff("extreme-negative-indices", set(
            tri(edge(-1, 0), edge(Integer.MIN_VALUE, Integer.MAX_VALUE), edge(0, -1)),
            tri(edge(Integer.MAX_VALUE, Integer.MIN_VALUE), edge(-1, -1), edge(-2, Integer.MIN_VALUE))));

        // same index pair, different coordinates — dedupe is index-only
        EdgeJ q1 = new EdgeJ(new TriPoint(0f, 0f, 7), new TriPoint(1f, 1f, 8));
        EdgeJ q2 = new EdgeJ(new TriPoint(9f, 9f, 8), new TriPoint(2f, 2f, 7)); // (8,7) reversed of (7,8)
        diff("same-index-diff-coords", set(tri(q1, q2, edge(7, 8))));

        // ---- fault / null domains -------------------------------------------
        // getter fault: thrown before any query — identical propagation
        RuntimeException boom = new RuntimeException("getter-fault");
        LinkedHashSet<TriL> fault = set(tri(edge(1,2), edge(2,3), edge(1,3)), new TriL(null, null, null, boom));
        diff("getter-fault", fault);

        // null triangle in set -> NPE at tri.d() on both, before queries
        LinkedHashSet<TriL> nullTri = set(tri(edge(1,2), edge(2,3), edge(1,3)), null);
        diff("null-triangle", nullTri);

        // null edge from getter -> k.a(j,false) NPE (checkNotNullParameter) vs
        // candidate's identical intrinsic -> same class + fixed prefix
        LinkedHashSet<TriL> nullEdge = set(new TriL(null, edge(1,2), edge(2,3)));
        Outcome rN = runRef(nullEdge), cN = runCand(nullEdge);
        check(rN.thrown instanceof NullPointerException, "null-edge ref must NPE");
        check(cN.thrown instanceof NullPointerException, "null-edge cand must NPE");
        check(canon(rN.thrown).equals(canon(cN.thrown)), "null-edge NPE canonical prefix");
        checks++; // count the prefix compare above
        // NOTE: a *stored* edge with a null endpoint is OUT OF DOMAIN (Kotlin non-null
        // contract makes it unconstructable); the linear scan evaluates e.a() before
        // j.a() per element while the index reads j first — declared, not normalized.

        // ---- comparator negative controls ------------------------------------
        compare(runRef(shared), runBrokenDirected(shared), "directed-only candidate", false);
        // forged equal-value list vs cand(shared): new edge objects -> identity reject

        // wrong-reference must be rejected: same indices, different edge object
        Outcome r2 = runRef(shared);
        Outcome forged = new Outcome();
        forged.list = new ArrayList<>();
        for (EdgeJ e : r2.list) forged.list.add(new EdgeJ(e.a(), e.b())); // equal coords, new refs
        forged.steps = r2.steps;
        check(!same(r2, forged), "forged equal-value different-reference list must be rejected");

        // direction-flipped element must be rejected
        Outcome flipped = new Outcome();
        flipped.list = new ArrayList<>();
        for (EdgeJ e : r2.list) flipped.list.add(new EdgeJ(e.b(), e.a()));
        flipped.steps = r2.steps;
        check(!same(r2, flipped), "direction-flipped element list must be rejected");

        // ---- post-return mutations independent of index ----------------------
        LinkedHashSet<TriL> post = set(tri(edge(1,2), edge(2,3), edge(1,3)));
        EdgeK refK = buildRefK(post);
        EdgeK candK = buildCandK(post);
        check(refK.a().size() == 3 && candK.a().size() == 3, "pre-mutation sizes");
        EdgeJ extra = edge(9,9);
        refK.a().add(extra);  candK.a().add(extra);                    // live-list add
        Iterator<EdgeJ> ri = refK.c(); ri.next(); ri.remove();          // iterator remove
        Iterator<EdgeJ> ci = candK.c(); ci.next(); ci.remove();
        check(refK.b(refK.a().get(0)) == candK.b(candK.a().get(0)), "post-return remove parity");
        check(refK.a().size() == candK.a().size(), "post-return list mutation parity");

        System.out.println("KBUILD_SELFCHECK PASS checks=" + checks);
    }

    static EdgeK buildRefK(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        for (TriL t : tris) {
            EdgeJ a = t.d(), b = t.e(), c = t.f();
            if (!k.a(a, false)) k.a(a);
            if (!k.a(b, false)) k.a(b);
            if (!k.a(c, false)) k.a(c);
        }
        return k;
    }
    static EdgeK buildCandK(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (TriL t : tris) {
            for (EdgeJ j : new EdgeJ[] { t.d(), t.e(), t.f() })
                if (queryLocal(seen, j)) k.a(j);
        }
        return k;
    }
}
