package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.kmembership.Fixture.CandTriangleList;
import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.Observer;
import dev.turboism.validation.kmembership.Fixture.RefTriangleList;
import dev.turboism.validation.kmembership.Fixture.TriL;
import dev.turboism.validation.kmembership.Fixture.TriPoint;

/**
 * Differential selfcheck driving the SINGLE delivered implementations
 * (Fixture.RefTriangleList/CandTriangleList) through an Observer tap.
 *
 * Every getter/query/append event is recorded with a snapshot of the full
 * ordered element-reference list taken right after the event; an escaping
 * throwable keeps the partial trace. Comparator negative controls must reject
 * deliberately-wrong candidates. Assertions are the only check counter.
 */
public final class SelfCheck {

    static int checks = 0;
    static void assertTrue(boolean cond, String what) {
        checks++;
        if (!cond) { System.out.println("FAIL " + what); System.exit(1); }
    }

    /** One recorded event; snapshot present for query/append steps. */
    static final class Step {
        final int kind;                 // 0 getter, 1 query, 2 append
        final Object arg;               // TriL for getter, EdgeJ otherwise
        final int which;                // getter ordinal
        final Boolean result;           // contained for query / add() result for append
        final List<EdgeJ> snapshot;     // full ordered reference list after the event
        Step(int kind, Object arg, int which, Boolean result, List<EdgeJ> snapshot) {
            this.kind = kind; this.arg = arg; this.which = which;
            this.result = result; this.snapshot = snapshot;
        }
    }

    static final class Outcome {
        final List<Step> steps = new ArrayList<>();
        List<EdgeJ> finalList;
        Throwable thrown;
    }

    /** Observer that records every event + post-event ordered list snapshot. */
    static final class Recorder implements Observer {
        final Outcome o = new Outcome();
        EdgeK live;                       // the k under construction
        public void created(EdgeK k) { live = k; }
        public void getter(TriL tri, int which) {
            o.steps.add(new Step(0, tri, which, null, null));
        }
        public void query(EdgeJ edge, boolean contained) {
            o.steps.add(new Step(1, edge, -1, contained, snap()));
        }
        public void append(EdgeJ edge, boolean added) {
            o.steps.add(new Step(2, edge, -1, added, snap()));
        }
        List<EdgeJ> snap() { return new ArrayList<>(live.a()); }
    }

    static Outcome runRef(LinkedHashSet<TriL> tris) {
        Recorder rec = new Recorder();
        try {
            rec.live = new RefTriangleList(tris).b(rec);
            rec.o.finalList = new ArrayList<>(rec.live.a());
        } catch (Throwable t) { rec.o.thrown = t; }
        return rec.o;
    }

    static Outcome runCand(LinkedHashSet<TriL> tris) {
        Recorder rec = new Recorder();
        try {
            rec.live = new CandTriangleList(tris).b(rec);
            rec.o.finalList = new ArrayList<>(rec.live.a());
        } catch (Throwable t) { rec.o.thrown = t; }
        return rec.o;
    }

    /** Deliberately wrong candidate — directed key only. Must be rejected. */
    static Outcome runBrokenDirected(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        Outcome o = new Outcome();
        try {
            for (TriL tri : tris) {
                for (EdgeJ j : new EdgeJ[] { tri.d(), tri.e(), tri.f() }) {
                    o.steps.add(new Step(0, tri, -1, null, null));
                    kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
                    int i0 = j.a().getIndex(), i1 = j.b().getIndex();
                    boolean contained = !seen.add(((long) i0 << 32) | (i1 & 0xffffffffL));
                    o.steps.add(new Step(1, j, -1, contained, new ArrayList<>(k.a())));
                    if (!contained) {
                        boolean added = k.a(j);
                        o.steps.add(new Step(2, j, -1, added, new ArrayList<>(k.a())));
                    }
                }
            }
            o.finalList = new ArrayList<>(k.a());
        } catch (Throwable t) { o.thrown = t; }
        return o;
    }

    /** Deliberately wrong candidate — queries run BEFORE the remaining getters are
     *  read (getter/query reorder). Must be rejected by event-order comparison. */
    static Outcome runBrokenReordered(LinkedHashSet<TriL> tris) {
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        Outcome o = new Outcome();
        try {
            for (TriL tri : tris) {
                for (int i = 0; i < 3; i++) {
                    EdgeJ j = i == 0 ? tri.d() : i == 1 ? tri.e() : tri.f();
                    o.steps.add(new Step(0, tri, i, null, null));
                    boolean contained = CandTriangleList.queryLocal(seen, j);
                    o.steps.add(new Step(1, j, -1, contained, new ArrayList<>(k.a())));
                    if (!contained)
                        o.steps.add(new Step(2, j, -1, k.a(j), new ArrayList<>(k.a())));
                }
            }
            o.finalList = new ArrayList<>(k.a());
        } catch (Throwable t) { o.thrown = t; }
        return o;
    }

    static void diff(String tag, LinkedHashSet<TriL> input) {
        assertTrue(same(runRef(input), runCand(input)), tag + " must match");
    }

    static void reject(String tag, Outcome ref, Outcome cand) {
        assertTrue(!same(ref, cand), tag + " (negative control) must be rejected");
    }

    static boolean same(Outcome a, Outcome b) {
        if (!sameThrowable(a.thrown, b.thrown)) return false;
        if (a.steps.size() != b.steps.size()) return false;
        for (int i = 0; i < a.steps.size(); i++) {
            Step sa = a.steps.get(i), sb = b.steps.get(i);
            if (sa.kind != sb.kind || sa.which != sb.which) return false;
            if (sa.arg != sb.arg) return false;                       // same input refs
            if (!java.util.Objects.equals(sa.result, sb.result)) return false;
            if ((sa.snapshot == null) != (sb.snapshot == null)) return false;
            if (sa.snapshot != null && !sameList(sa.snapshot, sb.snapshot)) return false;
        }
        if ((a.finalList == null) != (b.finalList == null)) return false;
        return a.finalList == null || sameList(a.finalList, b.finalList);
    }

    static boolean sameList(List<EdgeJ> x, List<EdgeJ> y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            EdgeJ ea = x.get(i), eb = y.get(i);
            if (ea != eb) return false;                              // identical reference
            if (ea.a() != eb.a() || ea.b() != eb.b()) return false;   // direction/endpoints
        }
        return true;
    }

    /** Exact throwable equality except two narrow documented normalizations:
     *  Kotlin intrinsic NPE keeps "Parameter specified as non-null is null" and
     *  the parameter name but the call-site method segment is mapped; implicit
     *  NPE "<localN>" slot names are slot-normalized. Everything else — including
     *  user-made fault messages — compares byte-exact. */
    static boolean sameThrowable(Throwable a, Throwable b) {
        if ((a == null) != (b == null)) return false;
        if (a == null) return true;
        if (!a.getClass().equals(b.getClass())) return false;
        String ma = a.getMessage(), mb = b.getMessage();
        if (java.util.Objects.equals(ma, mb)) return true;
        if (ma == null || mb == null) return false;
        // kotlin intrinsic form: keep prefix + parameter name, normalize call-site method
        String pa = kotlinNorm(ma), pb = kotlinNorm(mb);
        if (pa != null && pb != null) return pa.equals(pb);
        // implicit NPE form: "Cannot invoke "..." because "<localN>" is null"
        pa = localNorm(ma); pb = localNorm(mb);
        if (pa != null) return pa.equals(pb);
        return false;
    }
    static String kotlinNorm(String m) {
        if (!m.startsWith("Parameter specified as non-null is null")) return null;
        return m.replaceAll("method [^,]+", "method ?");
    }
    static String localNorm(String m) {
        if (!m.startsWith("Cannot invoke")) return null;
        return m.replaceAll("<local\\d+>", "<local>");
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
        // ---- in-domain differential matrix (same input feeds both runs) ------
        diff("empty", set());
        diff("single-tri", set(tri(edge(1,2), edge(2,3), edge(1,3))));

        LinkedHashSet<TriL> shared = set(
            tri(edge(1,2), edge(2,3), edge(1,3)),
            tri(edge(3,2), edge(2,4), edge(3,4)));   // edge (2,3) reused reversed as (3,2)
        diff("shared-reversed-edge", shared);

        EdgeJ dup = edge(5,9);
        diff("dup-and-equal-index", set(tri(dup, dup, edge(5,9))));
        diff("degenerate", set(tri(edge(4,4), edge(4,5), edge(5,5))));
        diff("extreme-negative-indices", set(
            tri(edge(-1, 0), edge(Integer.MIN_VALUE, Integer.MAX_VALUE), edge(0, -1)),
            tri(edge(Integer.MAX_VALUE, Integer.MIN_VALUE), edge(-1, -1), edge(-2, Integer.MIN_VALUE))));

        EdgeJ q1 = new EdgeJ(new TriPoint(0f, 0f, 7), new TriPoint(1f, 1f, 8));
        EdgeJ q2 = new EdgeJ(new TriPoint(9f, 9f, 8), new TriPoint(2f, 2f, 7));
        diff("same-index-diff-coords", set(tri(q1, q2, edge(7, 8))));

        // ---- fault / null domains -------------------------------------------
        // getter faults at EACH of the three positions — same throw, same trace point
        for (int at = 1; at <= 3; at++) {
            LinkedHashSet<TriL> fault = set(
                tri(edge(1,2), edge(2,3), edge(1,3)),
                new TriL(edge(7,8), edge(8,9), edge(7,9), at,
                         new RuntimeException("getter-fault-at-" + at)));
            diff("getter-fault-" + at, fault);
        }
        // distinct user fault messages must NOT merge (was the ':'-truncation bug)
        Outcome fa = runRef(set(new TriL(null, null, null, 1, new RuntimeException("getter:A"))));
        Outcome fb = runCand(set(new TriL(null, null, null, 1, new RuntimeException("getter:B"))));
        reject("distinct fault messages must differ", fa, fb);

        // null triangle in set -> NPE at tri.d() on both, before any query
        diff("null-triangle", set(tri(edge(1,2), edge(2,3), edge(1,3)), null));

        // null edge from getter -> NPE via checkNotNullParameter on both paths
        LinkedHashSet<TriL> nullEdge = set(new TriL(null, edge(1,2), edge(2,3)));
        Outcome rN = runRef(nullEdge), cN = runCand(nullEdge);
        assertTrue(rN.thrown instanceof NullPointerException, "null-edge ref must NPE");
        assertTrue(cN.thrown instanceof NullPointerException, "null-edge cand must NPE");
        assertTrue(rN.thrown.getMessage() != null
                   && rN.thrown.getMessage().contains("Parameter specified as non-null"),
                   "null-edge ref intrinsic NPE");
        assertTrue(sameThrowable(rN.thrown, cN.thrown), "null-edge NPE narrow-normalized equal");
        // OUT OF DOMAIN (declared, not normalized): a STORED edge whose a()/b() returns
        // null — unconstructable under the Kotlin non-null contract; the linear scan
        // evaluates stored-endpoint accessors per element while the index reads the
        // query edge's endpoints, so fault order cannot be made identical here.

        // ---- comparator negative controls ------------------------------------
        reject("directed-only candidate", runRef(shared), runBrokenDirected(shared));

        Outcome r2 = runRef(shared), c2 = runCand(shared);
        // forged list: equal-index NEW edge objects -> identity reject
        Outcome forged = new Outcome();
        forged.finalList = new ArrayList<>();
        for (EdgeJ e : c2.finalList) forged.finalList.add(new EdgeJ(e.a(), e.b()));
        forged.steps.addAll(c2.steps);
        reject("forged equal-value different-reference list", r2, forged);
        // direction-flipped elements -> reject
        Outcome flipped = new Outcome();
        flipped.finalList = new ArrayList<>();
        for (EdgeJ e : c2.finalList) flipped.finalList.add(new EdgeJ(e.b(), e.a()));
        flipped.steps.addAll(c2.steps);
        reject("direction-flipped element list", r2, flipped);
        // reordered getter/query sequence -> reject via step order
        reject("reordered getter/query candidate", runRef(shared), runBrokenReordered(shared));
        // steps-dropped forgery -> reject (steps vs real trace length)
        Outcome shortTrace = new Outcome();
        shortTrace.finalList = c2.finalList;
        shortTrace.steps.addAll(c2.steps.subList(0, c2.steps.size() - 1));
        reject("truncated step trace", r2, shortTrace);

        // ---- post-return mutations independent of index -----------------------
        LinkedHashSet<TriL> post = set(
            tri(edge(1,2), edge(2,3), edge(1,3)),
            tri(edge(3,2), edge(2,4), edge(3,4)));
        EdgeK refK = new RefTriangleList(post).b();
        EdgeK candK = new CandTriangleList(post).b();
        assertTrue(sameList(refK.a(), candK.a()), "post-return initial parity");
        EdgeJ extra = edge(9, 9);
        refK.a().add(extra); candK.a().add(extra);                       // live-list add
        Iterator<EdgeJ> ri = refK.c(); ri.next(); ri.remove();           // iterator remove
        Iterator<EdgeJ> ci = candK.c(); ci.next(); ci.remove();
        assertTrue(refK.b(refK.a().get(0)) == candK.b(candK.a().get(0)), "post-return remove parity");
        assertTrue(sameList(refK.a(), candK.a()), "post-return full ordered reference parity");

        System.out.println("KBUILD_SELFCHECK PASS checks=" + checks);
    }
}
