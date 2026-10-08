package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import org.objectweb.asm.Opcodes;

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

    /** Deliberately wrong candidate — identical structure to CandTriangleList.b()
     *  (three getters first, same ordinals, same event/snapshot pattern); only the
     *  membership key keeps direction. Must be rejected on reversed-duplicate input. */
    static boolean queryDirected(java.util.HashSet<Long> seen, EdgeJ j) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
        int i0 = j.a().getIndex(), i1 = j.b().getIndex();
        return !seen.add(((long) i0 << 32) | (i1 & 0xffffffffL));
    }
    static Outcome runBrokenDirected(LinkedHashSet<TriL> tris) {
        Recorder rec = new Recorder();
        EdgeK k = new EdgeK();
        rec.live = k;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        try {
            for (TriL tri : tris) {
                rec.getter(tri, 0); EdgeJ j4 = tri.d();
                rec.getter(tri, 1); EdgeJ j5 = tri.e();
                rec.getter(tri, 2); EdgeJ j6 = tri.f();
                boolean p4 = queryDirected(seen, j4); rec.query(j4, p4);
                if (!p4) rec.append(j4, k.a(j4));
                boolean p5 = queryDirected(seen, j5); rec.query(j5, p5);
                if (!p5) rec.append(j5, k.a(j5));
                boolean p6 = queryDirected(seen, j6); rec.query(j6, p6);
                if (!p6) rec.append(j6, k.a(j6));
            }
            rec.o.finalList = new ArrayList<>(k.a());
        } catch (Throwable t) { rec.o.thrown = t; }
        return rec.o;
    }

    /** Deliberately wrong candidate — queries run BEFORE the remaining getters are
     *  read (getter/query reorder). Must be rejected by event-order comparison. */
    static Outcome runBrokenReordered(LinkedHashSet<TriL> tris) {
        Recorder rec = new Recorder();
        EdgeK k = new EdgeK();
        rec.live = k;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        try {
            for (TriL tri : tris) {
                for (int i = 0; i < 3; i++) {
                    EdgeJ j = i == 0 ? tri.d() : i == 1 ? tri.e() : tri.f();
                    rec.getter(tri, i);
                    boolean contained = CandTriangleList.queryLocal(seen, j);
                    rec.query(j, contained);
                    if (!contained) rec.append(j, k.a(j));
                }
            }
            rec.o.finalList = new ArrayList<>(k.a());
        } catch (Throwable t) { rec.o.thrown = t; }
        return rec.o;
    }

    static void diff(String tag, LinkedHashSet<TriL> input) {
        assertTrue(same(runRef(input), runCand(input)), tag + " must match");
    }

    static void reject(String tag, Outcome ref, Outcome cand) {
        assertTrue(!same(ref, cand), tag + " (negative control) must be rejected");
    }

    /** First differing step index across kind/which/arg/result/snapshot, or -1. */
    static int firstDivergence(Outcome a, Outcome b) {
        int n = Math.min(a.steps.size(), b.steps.size());
        for (int i = 0; i < n; i++) {
            Step sa = a.steps.get(i), sb = b.steps.get(i);
            if (sa.kind != sb.kind || sa.which != sb.which) return i;
            if (sa.arg != sb.arg) return i;
            if (!java.util.Objects.equals(sa.result, sb.result)) return i;
            if ((sa.snapshot == null) != (sb.snapshot == null)) return i;
            if (sa.snapshot != null && !sameList(sa.snapshot, sb.snapshot)) return i;
        }
        return a.steps.size() == b.steps.size() ? -1 : n;
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
        assertTrue(same(rN, cN), "null-edge full trace equal (getters recorded, no query)");
        // OUT OF DOMAIN (declared, not normalized): a STORED edge whose a()/b() returns
        // null — unconstructable under the Kotlin non-null contract; the linear scan
        // evaluates stored-endpoint accessors per element while the index reads the
        // query edge's endpoints, so fault order cannot be made identical here.

        // ---- comparator negative controls ------------------------------------
        // directed-key candidate: identical to Ref on inputs without reversed duplicates…
        LinkedHashSet<TriL> noReversed = set(tri(edge(1,2), edge(2,3), edge(1,3)));
        assertTrue(same(runRef(noReversed), runBrokenDirected(noReversed)),
            "directed candidate agrees on input without reversed duplicates");
        // …and must be rejected where a reversed shared edge flips a query result.
        {
            Outcome dr = runRef(shared), dc = runBrokenDirected(shared);
            assertTrue(!same(dr, dc), "directed-only candidate rejected on reversed edge");
            int d = firstDivergence(dr, dc);
            assertTrue(d >= 0, "directed-only divergence located");
            Step sd = dr.steps.get(d);
            assertTrue(sd.kind == 1 || sd.kind == 2,
                "directed-only first divergence is a query/append step, not getter");
        }

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

        wovenSuite(shared);

        System.out.println("KBUILD_SELFCHECK PASS checks=" + checks);
    }

    // ======================= woven-target acceptance =========================

    static java.lang.Class<?> wovenClass;

    /** Load the woven WeaveTarget bytes through a child loader (defines the class
     *  itself; model classes + Helper resolve through the parent when present). */
    static java.lang.Class<?> loadWoven(byte[] bytes) throws Exception {
        String name = "dev.turboism.validation.kmembership.WeaveTarget";
        // child-first for the target name only — otherwise parent delegation would
        // silently load the UNWOVEN copy from classes-main.
        ClassLoader cl = new ClassLoader(SelfCheck.class.getClassLoader()) {
            @Override public Class<?> loadClass(String n, boolean resolve)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    if (c == null && n.equals(name))
                        c = defineClass(n, bytes, 0, bytes.length);
                    if (c == null) c = super.loadClass(n, false);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
        return Class.forName(name, true, cl);
    }

    static byte[] targetBytes() throws Exception {
        String res = "/dev/turboism/validation/kmembership/WeaveTarget.class";
        try (java.io.InputStream is = SelfCheck.class.getResourceAsStream(res)) {
            return is.readAllBytes();
        }
    }

    static final class WovenOutcome {
        List<EdgeJ> list; Throwable thrown;
    }
    static WovenOutcome runWoven(LinkedHashSet<TriL> tris) {
        WovenOutcome o = new WovenOutcome();
        try {
            Object tl = wovenClass.getDeclaredConstructor(LinkedHashSet.class)
                .newInstance(tris);
            EdgeK k = (EdgeK) wovenClass.getMethod("b").invoke(tl);
            o.list = new ArrayList<>(k.a());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            o.thrown = ite.getCause();
        } catch (Throwable t) { o.thrown = t; }
        return o;
    }

    static void wovenSuite(LinkedHashSet<TriL> shared) {
        try { wovenClass = loadWoven(Weave.weave(targetBytes())); }
        catch (Throwable t) { System.out.println("FAIL weave/load " + t); System.exit(1); return; }

        // normal path: helper really runs (not full fallback)
        resetAll();
        LinkedHashSet<TriL> in = set(
            tri(edge(1,2), edge(2,3), edge(1,3)),
            tri(edge(3,2), edge(2,4), edge(3,4)));
        WovenOutcome w = runWoven(in);
        assertTrue(w.thrown == null, "woven normal no throw");
        assertTrue(Helper.NEWBOX_CALLS.get() == 1, "woven newBox=1 got " + Helper.NEWBOX_CALLS.get());
        assertTrue(Helper.QUERIES.get() > 0, "woven helperQuery>0");
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 0,
            "woven originalQuery=0 got " + EdgeK.ORIGINAL_QUERIES.get());
        Outcome r = runRef(in);
        assertTrue(w.list != null && sameList(w.list, r.finalList), "woven ordered reference parity");

        // reversed-duplicate edge hits the index (membership) but original edge ref/order kept.
        // Direct proof: exactly one undirected key hit inside Helper + exactly 5 unique edges —
        // NOT inferred from "same length as reference".
        assertTrue(Helper.HITS.get() == 1,
            "helper undirected key hit==1 got " + Helper.HITS.get());
        assertTrue(w.list.size() == 5, "woven dedupe size==5 got " + w.list.size());
        assertTrue(w.list.size() == r.finalList.size(), "woven dedupe count parity");
        for (int i = 0; i < w.list.size(); i++)
            assertTrue(w.list.get(i) == r.finalList.get(i), "woven ref-identity idx" + i);

        // init LinkageError -> every site takes original path exactly once
        resetAll();
        Helper.failNewBox = true;
        WovenOutcome f = runWoven(in);
        assertTrue(f.thrown == null, "newbox-fail no throw");
        assertTrue(Helper.QUERIES.get() == 0, "newbox-fail helperQuery=0");
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 6, "newbox-fail original=6 got "
            + EdgeK.ORIGINAL_QUERIES.get());
        assertTrue(sameList(f.list, runRef(in).finalList), "newbox-fail parity");
        Helper.failNewBox = false;

        // LinkageError at query #N -> permanent local null, no more helper calls
        resetAll();
        Helper.failQueryAt = 5;
        WovenOutcome f5 = runWoven(in);   // 6 query positions; failure at 5th
        assertTrue(f5.thrown == null, "fail@5 no throw");
        assertTrue(Helper.QUERIES.get() == 5, "fail@5 helperQuery==5 got " + Helper.QUERIES.get());
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 2,
            "fail@5 original==2 got " + EdgeK.ORIGINAL_QUERIES.get());
        // result list still matches the unwoven reference element-by-element
        assertTrue(sameList(f5.list, runRef(in).finalList), "fail@5 result parity vs reference");
        Helper.failQueryAt = -1;
        // the disable is per-call only: the NEXT b() call re-inits a fresh box and
        // the helper path works normally again. ORIGINAL asserted BEFORE the
        // parity runRef — the reference run itself consumes ORIGINAL_QUERIES.
        int origAfterFail = EdgeK.ORIGINAL_QUERIES.get();
        WovenOutcome again = runWoven(in);
        assertTrue(again.thrown == null, "post-fail@5 no throw");
        assertTrue(Helper.NEWBOX_CALLS.get() == 2,
            "post-fail@5 fresh box got " + Helper.NEWBOX_CALLS.get());
        assertTrue(Helper.QUERIES.get() == 11,
            "post-fail@5 helper queries resumed got " + Helper.QUERIES.get());
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == origAfterFail,
            "post-fail@5 no extra original got " + EdgeK.ORIGINAL_QUERIES.get());
        assertTrue(sameList(again.list, runRef(in).finalList), "post-fail@5 parity");

        // null edge -> original query -> real NPE
        resetAll();
        WovenOutcome n = runWoven(set(new TriL(null, edge(1,2), edge(2,3))));
        assertTrue(n.thrown instanceof NullPointerException, "woven null-edge NPE");
        assertTrue(n.thrown.getMessage() != null
            && n.thrown.getMessage().contains("Parameter specified as non-null"),
            "woven null-edge intrinsic NPE");
        assertTrue(Helper.QUERIES.get() == 0, "null-edge helperQuery=0");

        // RuntimeException propagates; no retry of original query
        resetAll();
        Helper.injectError = 1;
        WovenOutcome re = runWoven(in);
        assertTrue(re.thrown instanceof RuntimeException
            && "injected-re".equals(re.thrown.getMessage()), "woven RE propagates");
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 0, "RE no original retry");
        Helper.injectError = 0;

        // ThreadDeath and VirtualMachineError propagate — same no-retry contract as RE
        resetAll();
        Helper.injectError = 2;
        WovenOutcome td = runWoven(in);
        assertTrue(td.thrown instanceof ThreadDeath, "woven ThreadDeath propagates");
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 0, "ThreadDeath no original retry");
        Helper.injectError = 0;
        resetAll();
        Helper.injectError = 3;
        WovenOutcome ve = runWoven(in);
        assertTrue(ve.thrown instanceof VirtualMachineError, "woven VMErr propagates");
        assertTrue(EdgeK.ORIGINAL_QUERIES.get() == 0, "VMErr no original retry");
        Helper.injectError = 0;

        // repeated b() calls: independent boxes
        resetAll();
        runWoven(in); runWoven(in);
        assertTrue(Helper.NEWBOX_CALLS.get() == 2, "two calls -> two boxes");
        assertTrue(Helper.QUERIES.get() == 12, "two calls -> 12 helper queries");

        // empty input: init runs once, zero queries; extra allocs reported
        resetAll();
        WovenOutcome e = runWoven(set());
        assertTrue(Helper.NEWBOX_CALLS.get() == 1 && Helper.QUERIES.get() == 0,
            "empty input newBox=1 query=0");
        System.out.println("KBUILD_WOVEN_EMPTY boxAllocs=" + Helper.BOX_ALLOCS.get()
            + " setAllocs=" + Helper.SET_ALLOCS.get());

        shapeRejects();
    }

    static void resetAll() { Helper.reset(); EdgeK.ORIGINAL_QUERIES.set(0); }

    /** Shape-gate negative controls: every variant must be rejected AT THE
     *  EXPECTED check — the observable reason from weaveChecked() — not merely
     *  byte-equal. weave(byte[]) keeps its contract alongside: original array. */
    static void shapeRejects() {
        try {
            byte[] orig = targetBytes();
            // Z-operand variants: constant-true AND a non-constant (ILOAD) form.
            expectReject("iconst1-Z", mutateZtoTrue(orig), "Z not iconst_0");
            expectReject("iload-Z", mutateZtoIload(orig), "Z not iconst_0");
            // retargeted query desc: the call stops matching QUERY_DESC so it is
            // no longer a pinned site; its trailing append then fails the
            // site-adjacency pin — that is the ACTUAL rejecting check.
            expectReject("retargeted-query-desc", retargetQueryDesc(orig),
                "not adjacent to pinned query site");
            // site count: drop the whole first query+append statement -> queries=2.
            expectReject("site-count-2", dropCompleteSite(orig), "queries=2");
            // anchor wrote a different slot than the sites load as k.
            expectReject("anchor-k-slot", mutateAnchorSlot(orig), "k slot");
            // a query site earlier than the k-init anchor.
            expectReject("site-before-anchor", injectSiteBeforeAnchor(orig),
                "before anchor");
            // append loads a different j slot than its own query site.
            expectReject("append-j-slot", mutateAppendJSlot(orig), "j slot");
            // no NEW/DUP/<init>/ASTORE sequence -> no anchor at all.
            expectReject("no-anchor", mutateDupToPop(orig), "no k-init anchor");
        } catch (Throwable t) { System.out.println("FAIL shapeRejects " + t); System.exit(1); }
    }

    static void expectReject(String tag, byte[] variant, String reasonPart) {
        Weave.Result r = Weave.weaveChecked(variant);
        assertTrue(r.rejectReason != null, tag + " must reject (no reason)");
        assertTrue(r.bytes == variant, tag + " reject returns the original array");
        assertTrue(r.rejectReason.contains(reasonPart),
            tag + " reason '" + r.rejectReason + "' must hit '" + reasonPart + "'");
        assertTrue(Weave.weave(variant) == variant,
            tag + " weave() also returns the original array");
        System.out.println("SHAPE_REJECT " + tag + " reason=" + r.rejectReason);
    }

    /** Replace first ICONST_0 in b() with ICONST_1 (true-operand variant). */
    static byte[] mutateZtoTrue(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                boolean patched;
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        @Override public void visitInsn(int op) {
                            if (!patched && op == org.objectweb.asm.Opcodes.ICONST_0) {
                                patched = true;
                                super.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
                                return;
                            }
                            super.visitInsn(op);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }
    /** Replace first ICONST_0 in b() with ILOAD 0 (non-constant operand variant). */
    static byte[] mutateZtoIload(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                boolean patched;
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        @Override public void visitInsn(int op) {
                            if (!patched && op == org.objectweb.asm.Opcodes.ICONST_0) {
                                patched = true;
                                super.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 0);
                                return;
                            }
                            super.visitInsn(op);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }
    /** Retarget the first query call's descriptor to (Ljava/lang/Object;Z)Z. */
    static byte[] retargetQueryDesc(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                boolean patched;
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String o,
                                String n, String d, boolean itf) {
                            if (!patched && op == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                    && o.equals(Weave.KTYPE)
                                    && d.equals(Weave.QUERY_DESC)) {
                                patched = true;
                                super.visitMethodInsn(op, o, n,
                                    "(Ljava/lang/Object;Z)Z", itf);
                                return;
                            }
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }
    /** Remove the whole first query statement: the 4-insn query sequence plus its
     *  conditional jump, the append pair, and the result POP — site count 2,
     *  append count 2 (the already-emitted 3-insn prefix becomes dead code). */
    static byte[] dropCompleteSite(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        int idx = -1;
                        int dropUntil = -1;
                        void tick() { idx++; }
                        boolean drop() { return idx <= dropUntil; }
                        @Override public void visitMethodInsn(int op, String o,
                                String n, String d, boolean itf) {
                            tick();
                            if (dropUntil < 0 && op == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                    && o.equals(Weave.KTYPE) && d.equals(Weave.QUERY_DESC))
                                dropUntil = idx + 5;   // invoke+jump+aload+aload+append+pop
                            if (!drop()) super.visitMethodInsn(op, o, n, d, itf);
                        }
                        @Override public void visitJumpInsn(int op,
                                org.objectweb.asm.Label l) {
                            tick(); if (!drop()) super.visitJumpInsn(op, l);
                        }
                        @Override public void visitInsn(int op) {
                            tick(); if (!drop()) super.visitInsn(op);
                        }
                        @Override public void visitVarInsn(int op, int v) {
                            tick(); if (!drop()) super.visitVarInsn(op, v);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }

    /** Patch the k-init anchor ASTORE to a different local slot: the anchor is
     *  still found but its slot no longer matches the sites' k loads. */
    static byte[] mutateAnchorSlot(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        boolean patched;
                        final java.util.List<int[]> trail = new java.util.ArrayList<>();
                        void tick(int op, int flags) {
                            trail.add(new int[]{op, flags});
                            if (trail.size() > 4) trail.remove(0);
                        }
                        @Override public void visitTypeInsn(int op, String t) {
                            tick(op, 0); super.visitTypeInsn(op, t);
                        }
                        @Override public void visitInsn(int op) {
                            tick(op, 0); super.visitInsn(op);
                        }
                        @Override public void visitJumpInsn(int op,
                                org.objectweb.asm.Label l) {
                            tick(op, 0); super.visitJumpInsn(op, l);
                        }
                        @Override public void visitIntInsn(int op, int v) {
                            tick(op, 0); super.visitIntInsn(op, v);
                        }
                        @Override public void visitMethodInsn(int op, String o,
                                String n, String d, boolean itf) {
                            tick(op, o.equals(Weave.KTYPE) && "<init>".equals(n) ? 1 : 0);
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                        @Override public void visitVarInsn(int op, int var) {
                            tick(op, 0);
                            int sz = trail.size();
                            if (!patched && op == org.objectweb.asm.Opcodes.ASTORE
                                    && sz >= 4
                                    && trail.get(sz - 2)[0] == org.objectweb.asm.Opcodes.INVOKESPECIAL
                                    && trail.get(sz - 2)[1] == 1
                                    && trail.get(sz - 3)[0] == org.objectweb.asm.Opcodes.DUP
                                    && trail.get(sz - 4)[0] == org.objectweb.asm.Opcodes.NEW) {
                                patched = true;
                                super.visitVarInsn(op, var + 16);
                                return;
                            }
                            super.visitVarInsn(op, var);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }

    /** Inject a full 4-insn query sequence BEFORE the k-init NEW — a site whose
     *  real-insn index precedes the anchor. */
    static byte[] injectSiteBeforeAnchor(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        boolean injected;
                        @Override public void visitTypeInsn(int op, String t) {
                            if (!injected && op == org.objectweb.asm.Opcodes.NEW
                                    && t.equals(Weave.KTYPE)) {
                                injected = true;
                                super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 40);
                                super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 41);
                                super.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
                                super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                                    Weave.KTYPE, "a", Weave.QUERY_DESC, false);
                            }
                            super.visitTypeInsn(op, t);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }

    /** Patch the first append's j load to a different slot: the append no longer
     *  references the same j as its own query site. */
    static byte[] mutateAppendJSlot(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        // 0 wait query, 1 saw query invoke, 2 saw jump,
                        // 3 saw append aload k -> next aload j is patched
                        int phase;
                        @Override public void visitMethodInsn(int op, String o,
                                String n, String d, boolean itf) {
                            if (phase == 0 && op == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                    && o.equals(Weave.KTYPE) && d.equals(Weave.QUERY_DESC))
                                phase = 1;
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                        @Override public void visitJumpInsn(int op,
                                org.objectweb.asm.Label l) {
                            if (phase == 1) phase = 2;
                            super.visitJumpInsn(op, l);
                        }
                        @Override public void visitVarInsn(int op, int var) {
                            if (phase == 2) phase = 3;
                            else if (phase == 3) {
                                phase = 4;
                                super.visitVarInsn(op, var + 16);
                                return;
                            }
                            super.visitVarInsn(op, var);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }

    /** Drop the DUP inside the NEW/DUP/<init>/ASTORE k-init sequence — the
     *  anchor pattern no longer matches anywhere. */
    static byte[] mutateDupToPop(byte[] in) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(in).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, cw) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int acc, String name, String desc, String sig, String[] exc) {
                    org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(acc, name, desc, sig, exc);
                    if (!name.equals("b") || mv == null) return mv;
                    return new org.objectweb.asm.MethodVisitor(
                            org.objectweb.asm.Opcodes.ASM9, mv) {
                        boolean afterNewK;
                        @Override public void visitTypeInsn(int op, String t) {
                            afterNewK = op == org.objectweb.asm.Opcodes.NEW
                                    && t.equals(Weave.KTYPE);
                            super.visitTypeInsn(op, t);
                        }
                        @Override public void visitInsn(int op) {
                            if (afterNewK && op == org.objectweb.asm.Opcodes.DUP) {
                                afterNewK = false;
                                super.visitInsn(org.objectweb.asm.Opcodes.POP);
                                return;
                            }
                            afterNewK = false;
                            super.visitInsn(op);
                        }
                        @Override public void visitMethodInsn(int op, String o,
                                String n, String d, boolean itf) {
                            afterNewK = false;
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                        @Override public void visitVarInsn(int op, int var) {
                            afterNewK = false;
                            super.visitVarInsn(op, var);
                        }
                    };
                }
            }, 0);
        return cw.toByteArray();
    }
}
