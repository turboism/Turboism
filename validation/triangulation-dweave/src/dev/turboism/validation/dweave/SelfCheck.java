package dev.turboism.validation.dweave;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import dev.turboism.validation.dweave.OwnWindow.Edge;
import dev.turboism.validation.dweave.OwnWindow.Observer;
import dev.turboism.validation.dweave.OwnWindow.Pt;
import dev.turboism.validation.dweave.OwnWindow.Tri;

/**
 * T029-DWEAVE offline self-check. Own classes only; official bytecode is
 * never read/loaded/executed here (that is OfficialProbe's read-only job).
 *
 * Sections:
 *  A. fixture shape discovery + weave acceptance + two-operand diff proof
 *  B. woven-vs-unwoven differential over the domain matrix (fresh loader,
 *     same input references; events, result ordinals, exceptions)
 *  C. MatchList pointwise unit contract vs ArrayList (incl. nulls) and the
 *     documented desync/equal-semantics boundaries
 *  D. shape-gate negative controls (mutants must reject with the expected
 *     reason, returning the original array)
 */
public final class SelfCheck {
    private SelfCheck() {}

    /** Binary (dot) name of the fixture class for classloading. */
    static final String OWN = "dev.turboism.validation.dweave.OwnWindow";
    /** Internal (slash) name of the helper as woven into the bytes. */
    static final String MATCHLIST = "dev/turboism/validation/dweave/MatchList";
    static final String LIST = "java/util/ArrayList";

    static int checks = 0;
    static void check(boolean cond, String name) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + name);
    }
    static void eq(Object a, Object b, String name) {
        checks++;
        if (!java.util.Objects.equals(a, b))
            throw new AssertionError("FAIL " + name + " expected=" + b + " actual=" + a);
    }

    public static void main(String[] args) throws Exception {
        byte[] orig = ownBytes();
        int site = fixtureAndWeave(orig);
        differential(orig, site);
        matchListUnit();
        mutants(orig, site);
        System.out.println("DWEAVE_SELFCHECK PASS checks=" + checks);
    }

    // ------------------------------------------------------------- A. weave
    /** Discovery + weave acceptance + operand-diff proof. Returns the pinned
     *  slot discovered on the fixture's first (Phase-3) site. */
    static int fixtureAndWeave(byte[] orig) {
        Weave.Config discovery = new Weave.Config("c", "()V", LIST, "()V",
            -1, -1, MATCHLIST);
        Weave.Plan p = Weave.collect(discovery, orig);
        check(p.methodFound, "fixture method c()V found");
        eq(p.sites.size(), 2, "fixture site count (Phase-3 + Phase-4 analog)");
        check(p.sites.get(0).astoreSlot != p.sites.get(1).astoreSlot,
            "fixture sites land on distinct slots");
        check(p.listTypeNews == 2, "fixture NEW ArrayList count == 2");
        int pinned = p.sites.get(0).astoreSlot;

        Weave.Config cfg = fixtureCfg(pinned);
        Weave.Result r = Weave.weaveChecked(cfg, orig);
        check(r.rejectReason == null, "fixture weave accepted: " + r.rejectReason);
        check(r.bytes != orig, "woven bytes are a new array");
        check(!Arrays.equals(r.bytes, orig), "woven bytes differ");
        eq(r.plan.target.astoreSlot, pinned, "accepted site is the pinned slot");

        // Exactly two instruction operands differ, both inside c()V at the
        // recorded site indices; every other method's stream is identical.
        List<String> diffs = InsnDiff.diff(orig, r.bytes);
        eq(diffs.size(), 2, "insn diff count");
        check(diffs.get(0).startsWith("c()V#" + p.sites.get(0).newIndex + ":")
                && diffs.get(0).contains("type:" + /*NEW*/187 + ":" + LIST)
                && diffs.get(0).contains("type:" + 187 + ":" + MATCHLIST),
            "NEW operand retargeted at site: " + diffs.get(0));
        check(diffs.get(1).startsWith("c()V#" + p.sites.get(0).initIndex + ":")
                && diffs.get(1).contains(LIST + ".<init>()V")
                && diffs.get(1).contains(MATCHLIST + ".<init>()V"),
            "INVOKESPECIAL owner retargeted at site: " + diffs.get(1));

        // Woven bytes re-analyzed: one MatchList site on the pinned slot,
        // the Phase-4 analog site still a plain ArrayList.
        Weave.Plan ml = Weave.collect(
            new Weave.Config("c", "()V", MATCHLIST, "()V", -1, -1, MATCHLIST), r.bytes);
        eq(ml.sites.size(), 1, "woven MatchList site count");
        eq(ml.sites.get(0).astoreSlot, pinned, "woven MatchList site slot");
        Weave.Plan al = Weave.collect(discovery, r.bytes);
        eq(al.sites.size(), 1, "woven leftover ArrayList site count");
        eq(al.sites.get(0).astoreSlot, p.sites.get(1).astoreSlot,
            "second site left unwoven");
        return pinned;
    }

    static Weave.Config fixtureCfg(int slot) {
        return new Weave.Config("c", "()V", LIST, "()V", slot, 2, MATCHLIST);
    }

    static byte[] ownBytes() throws Exception {
        try (InputStream is = OwnWindow.class.getResourceAsStream("OwnWindow.class")) {
            check(is != null, "own class resource present");
            return is.readAllBytes();
        }
    }

    // ------------------------------------------------------ B. differential
    /** Child-first loader ONLY for the top-level fixture class; every other
     *  type (nested Edge/Tri/Pt, MatchList, Intrinsics) delegates to the
     *  parent so woven/unwoven runs share type identity. */
    static final class ByteLoader extends ClassLoader {
        private final String target;
        private final byte[] bytes;
        ByteLoader(ClassLoader p, String target, byte[] bytes) {
            super(p); this.target = target; this.bytes = bytes;
        }
        @Override protected Class<?> loadClass(String n, boolean resolve)
                throws ClassNotFoundException {
            if (n.equals(target)) {
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    if (c == null) c = defineClass(n, bytes, 0, bytes.length);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(n, resolve);
        }
    }

    /** Per-run event recorder with identity-ordinal normalization so two runs
     *  (each constructing its own candidate objects) compare structurally. */
    static final class Rec implements Observer {
        final List<String> events = new ArrayList<>();
        final IdentityHashMap<Object, Integer> ord = new IdentityHashMap<>();
        String id(Object o) {
            Integer v = ord.get(o);
            if (v == null) { v = ord.size(); ord.put(o, v); }
            return "#" + v;
        }
        @Override public void edgeNext(Edge e) { events.add("edgeNext:" + id(e)); }
        @Override public void triNext(Tri t) { events.add("triNext:" + id(t)); }
        @Override public void candidate(int w, Edge e) { events.add("cand:" + w + ":" + id(e)); }
        @Override public void flag(int w, boolean v) { events.add("flag:" + w + ":" + v); }
        @Override public void share(int w, boolean v) { events.add("share:" + w + ":" + v); }
        @Override public void query(int w, boolean v) { events.add("query:" + w + ":" + v); }
        @Override public void append(int w, Edge e) { events.add("append:" + w + ":" + id(e)); }
        @Override public void phase4Pop(Edge e) { events.add("pop:" + id(e)); }
        @Override public void phase4Readd(Edge e) { events.add("readd:" + id(e)); }
        @Override public void phase5AddAll(int n) { events.add("addAll:" + n); }
        List<String> ordinalsOf(List<?> l, String tag) {
            List<String> out = new ArrayList<>();
            if (l == null) { out.add(tag + ":null"); return out; }
            for (Object o : l) out.add(tag + ":" + id(o));
            return out;
        }
    }

    static final class Outcome {
        List<String> events, matchOrdinals, poppedOrdinals;
        String exc, matchClass, poppedClass;
    }

    /** Runs c() on {@code cls} (unwoven class or woven-bytes class) with the
     *  given shared input references; captures events, results, exception. */
    static Outcome run(Class<?> cls, List<Edge> edges, LinkedHashSet<Tri> tris,
            List<Edge> extra) throws Exception {
        Rec obs = new Rec();
        Outcome o = new Outcome();
        Object inst = cls.getDeclaredConstructor(
            List.class, LinkedHashSet.class, List.class, Observer.class)
            .newInstance(edges, tris, extra, obs);
        try {
            cls.getMethod("c").invoke(inst);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            o.exc = c.getClass().getName() + "|" + c.getMessage();
        }
        Object ml = cls.getDeclaredField("matchListOut").get(inst);
        Object pp = cls.getDeclaredField("poppedOut").get(inst);
        o.events = obs.events;
        o.matchOrdinals = obs.ordinalsOf((List<?>) ml, "ml");
        o.poppedOrdinals = obs.ordinalsOf((List<?>) pp, "pp");
        o.matchClass = ml == null ? "null" : ml.getClass().getName();
        o.poppedClass = pp == null ? "null" : pp.getClass().getName();
        return o;
    }

    static void domain(String name, Class<?> woven, List<Edge> edges,
            LinkedHashSet<Tri> tris, List<Edge> extra) throws Exception {
        Outcome a = run(OwnWindow.class, edges, tris, extra);
        Outcome b = run(woven, edges, tris, extra);
        checks++;
        StringBuilder why = new StringBuilder();
        boolean same = a.events.equals(b.events)
            && a.matchOrdinals.equals(b.matchOrdinals)
            && a.poppedOrdinals.equals(b.poppedOrdinals)
            && java.util.Objects.equals(a.exc, b.exc);
        if (!same) why.append(" eventsEq=").append(a.events.equals(b.events))
            .append(" mlEq=").append(a.matchOrdinals.equals(b.matchOrdinals))
            .append(" ppEq=").append(a.poppedOrdinals.equals(b.poppedOrdinals))
            .append(" excEq=").append(java.util.Objects.equals(a.exc, b.exc))
            .append(" exc=").append(a.exc).append("/").append(b.exc);
        if (!same) throw new AssertionError("FAIL domain " + name + why);
        // The weave really took: matchList is a MatchList in the woven run
        // (unless the domain early-returns), the Phase-4 list stays ArrayList.
        if (!"null".equals(a.matchClass)) {
            eq(b.matchClass, MATCHLIST.replace('/', '.'), "domain " + name + " woven ml class");
            eq(a.matchClass, "java.util.ArrayList", "domain " + name + " unwoven ml class");
            eq(b.poppedClass, "java.util.ArrayList", "domain " + name + " woven popped class");
        } else {
            eq(b.matchClass, "null", "domain " + name + " woven ml null");
        }
    }

    static Pt pt(float x, float y, int i) { return new Pt(x, y, i); }
    static Edge e(Pt a, Pt b) { return new Edge(a, b); }
    static Edge e(float x1, float y1, int i1, float x2, float y2, int i2) {
        return new Edge(pt(x1, y1, i1), pt(x2, y2, i2));
    }
    static LinkedHashSet<Tri> tris(Tri... t) {
        return new LinkedHashSet<>(Arrays.asList(t));
    }
    static List<Edge> edges(Edge... e) { return new ArrayList<>(Arrays.asList(e)); }

    static void differential(byte[] orig, int pinned) throws Exception {
        Weave.Result r = Weave.weaveChecked(fixtureCfg(pinned), orig);
        check(r.rejectReason == null, "differential weave accepted");
        Class<?> woven = new ByteLoader(SelfCheck.class.getClassLoader(), OWN, r.bytes)
            .loadClass(OWN);
        check(woven != OwnWindow.class, "woven class is a fresh definition");
        check(woven.getClassLoader() != OwnWindow.class.getClassLoader(),
            "woven loaded by child loader");

        List<Edge> none = Collections.emptyList();

        // edges == null: early return before the site (official bci 58-63).
        domain("nullEdges", woven, null, tris(tA()), none);
        // empty domains.
        domain("emptyBoth", woven, edges(), tris(), none);
        domain("emptyEdges", woven, edges(), tris(tA()), none);
        domain("emptyTris", woven, edges(e1()), tris(), none);
        // one crossing edge x one triangle -> eAB and eBC append, eCA no flag.
        domain("basic1x1", woven, edges(ejCross()), tris(tA()), none);
        // duplicate-candidate domain: two tris sharing the AB segment ->
        // same-endpoint DISTINCT objects both appended (contains stays false:
        // identity membership, the official dead-check semantics).
        domain("dupCandidates", woven, edges(ejCross()),
            tris(tA(), tDupAB()), none);
        // share-skip: ej shares an endpoint INDEX with every candidate.
        domain("shareSkip", woven, edges(ejShare()), tris(tA()), none);
        // int-extreme endpoint indices (MIN_VALUE / -1 / 0) through the gates.
        domain("intExtremes", woven, edges(ejExtreme()), tris(tExtreme()), none);
        // null element inside the edges list -> intrinsic NPE mid-window.
        domain("nullEdgeElem", woven,
            new ArrayList<>(Arrays.asList(e1(), null, e(0, 9, 30, 9, 9, 31))),
            tris(tA()), none);
        // null element inside the triangle set -> intrinsic NPE.
        LinkedHashSet<Tri> trisNull = tris(tA());
        trisNull.add(null);
        domain("nullTriElem", woven, edges(ejCross()), trisNull, none);
        // null point getter -> j.<init>-style checkNotNullParameter NPE.
        domain("nullPointGetter", woven, edges(ejCross()),
            tris(Tri.withNullGetter(1)), none);
        // Phase-5 analog: non-empty addAll into matchList after the window.
        domain("phase5AddAll", woven, edges(ejCross()), tris(tA()),
            edges(e(20, 20, 90, 21, 21, 91), e(22, 22, 92, 23, 23, 93)));
        // combined load: 5 edges x 5 tris, mixed hit/share/miss.
        List<Edge> es = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            es.add(e(i * 4f - 8f, i - 2f, 100 + i, i * 4f + 4f, i - 1f, 200 + i));
        LinkedHashSet<Tri> ts = tris(tA(), tDupAB(), tFar(), tShare(), tExtreme());
        domain("mixed5x5", woven, es, ts,
            edges(e(50, 50, 95, 51, 51, 96)));
    }

    // ----- fixture domains ------------------------------------------------
    // Geometry notes: ejCross is the horizontal segment (0,0)-(10,0).
    // tA = {(0,-5),(10,5),(5,-5)}: eAB crosses y=0 at x=5, eBC at x=7.5,
    // eCA is the y=-5 horizontal (no cross). Indices 3,4,5 vs ej {1,2}.
    static Edge e1() { return e(0, 1, 10, 1, 1, 11); }
    static Edge ejCross() { return e(0, 0, 1, 10, 0, 2); }
    static Edge ejShare() { return e(0, 0, 3, 10, 0, 2); }   // index 3 shared with tA.a
    static Edge ejExtreme() {
        return e(0, 0, Integer.MIN_VALUE, 10, 0, 0);
    }
    static Tri tA() { return new Tri(pt(0, -5, 3), pt(10, 5, 4), pt(5, -5, 5)); }
    /** Second triangle with the SAME a/b endpoints as tA -> duplicate
     *  candidate objects (distinct refs) for the contains dead-check domain. */
    static Tri tDupAB() { return new Tri(pt(0, -5, 3), pt(10, 5, 4), pt(9, -5, 6)); }
    static Tri tFar() { return new Tri(pt(100, 100, 40), pt(110, 100, 41), pt(105, 110, 42)); }
    /** Triangle whose candidates share ejCross's indices {1,2}. */
    static Tri tShare() { return new Tri(pt(1, -1, 1), pt(11, 1, 7), pt(5, -1, 2)); }
    static Tri tExtreme() {
        return new Tri(pt(0, -2, Integer.MIN_VALUE), pt(10, 2, -1), pt(5, -2, 0));
    }

    // ------------------------------------------------- C. MatchList unit
    /** Element flavour with value-equals (contract-boundary evidence). */
    static final class ValEq {
        final int k;
        ValEq(int k) { this.k = k; }
        @Override public boolean equals(Object o) {
            return o instanceof ValEq && ((ValEq) o).k == k;
        }
        @Override public int hashCode() { return k; }
    }

    static void matchListUnit() {
        // --- pointwise add-only contract incl. null -------------------------
        List<Object> ref = new ArrayList<>();
        List<Object> ml = new MatchList<>();
        Object a = new Object(), b = new Object();
        List<String> ra = new ArrayList<>(), rb = new ArrayList<>();
        Object[][] script = {
            {"contains", a}, {"add", a}, {"contains", a}, {"contains", b},
            {"add", null}, {"contains", null}, {"add", a}, {"contains", a},
            {"add", null}, {"contains", null}, {"add", b}, {"contains", b},
            {"isEmpty", null}, {"size", null}, {"get0", null},
        };
        for (Object[] op : script) {
            ra.add(apply(ref, op)); rb.add(apply(ml, op));
        }
        // snapshot iteration order (identity-tagged)
        ra.add("iter:" + ref.size()); rb.add("iter:" + ml.size());
        Iterator<Object> ir = ref.iterator(), im = ml.iterator();
        while (ir.hasNext()) {
            Object x = ir.next(), y = im.next();
            ra.add(x == a ? "a" : x == b ? "b" : "null");
            rb.add(y == a ? "a" : y == b ? "b" : "null");
        }
        eq(rb, ra, "MatchList pointwise parity (add-only, incl null)");

        // IS-A checks through supertypes.
        ArrayList<Object> asArrayList = new MatchList<>();
        check(asArrayList instanceof ArrayList, "MatchList IS-A ArrayList");
        check(asArrayList instanceof List, "MatchList IS-A List");
        check(asArrayList instanceof java.util.Collection, "MatchList IS-A Collection");
        check(((java.util.Collection<Object>) asArrayList).isEmpty(),
            "interface-dispatch isEmpty");
        asArrayList.add(a);
        check(!asArrayList.isEmpty(), "isEmpty after add");
        check(asArrayList.contains(a) && !asArrayList.contains(b),
            "contains via ArrayList ref dispatches to mirror");

        // --- documented boundaries (divergence is expected, javap-pinned) ---
        // (1) equals-typed elements: mirror is identity -> NOT equivalent.
        List<Object> refV = new ArrayList<>(); refV.add(new ValEq(7));
        List<Object> mlV = new MatchList<>(); mlV.add(new ValEq(7));
        check(refV.contains(new ValEq(7)), "boundary: equals hit on ArrayList");
        check(!mlV.contains(new ValEq(7)),
            "boundary: identity mirror misses equals-equal (contract limit)");
        // (2) remove() desyncs the mirror — unobservable in the official
        // window because no contains executes after the window (javap).
        List<Object> refR = new ArrayList<>(); List<Object> mlR = new MatchList<>();
        refR.add(a); mlR.add(a); refR.remove(0); mlR.remove(0);
        check(!refR.contains(a) && mlR.contains(a),
            "boundary: post-remove contains stale (post-window only, unobservable)");
        // (3) addAll bypasses add -> stale mirror, same unobservability.
        List<Object> refA = new ArrayList<>(); List<Object> mlA = new MatchList<>();
        refA.addAll(Arrays.asList(a)); mlA.addAll(Arrays.asList(a));
        check(refA.contains(a) && !mlA.contains(a),
            "boundary: post-addAll contains stale (post-window only, unobservable)");
    }

    static String apply(List<Object> l, Object[] op) {
        String name = (String) op[0];
        switch (name) {
            case "add": return "add:" + l.add(op[1]);
            case "contains": return "contains:" + l.contains(op[1]);
            case "isEmpty": return "isEmpty:" + l.isEmpty();
            case "size": return "size:" + l.size();
            case "get0": {
                Object g = l.get(0);
                return "get0:" + (g == null ? "null" : "obj");
            }
            default: throw new AssertionError("bad op " + name);
        }
    }

    // ------------------------------------------------------------- D. mutants
    static void mutants(byte[] orig, int pinned) {
        reject("dropPinnedDup", ShapeMutants.dropPinnedDup(orig),
            "no pinned init site", orig, pinned);
        reject("retargetPinnedSlot", ShapeMutants.retargetPinnedSlot(orig),
            "no pinned init site", orig, pinned);
        reject("duplicatePinnedSite", ShapeMutants.duplicatePinnedSite(orig),
            "ambiguous pinned init site", orig, pinned);
        reject("wrongCtorDesc", ShapeMutants.wrongCtorDesc(orig),
            "no pinned init site", orig, pinned);
        reject("otherNewType", ShapeMutants.otherNewType(orig),
            "no pinned init site", orig, pinned);
        reject("gapBeforeDup", ShapeMutants.gapBeforeDup(orig),
            "no pinned init site", orig, pinned);
        // foreign class (no c()V) -> method-not-found
        byte[] foreign;
        try (InputStream is = MatchList.class.getResourceAsStream("MatchList.class")) {
            foreign = is.readAllBytes();
        } catch (Exception e) { throw new AssertionError(e); }
        reject("noMethod", foreign, "method-not-found", orig, pinned);
        // legit shape but wrong total-site expectation
        Weave.Result t = Weave.weaveChecked(
            new Weave.Config("c", "()V", LIST, "()V", pinned, 99, MATCHLIST), orig);
        check(t.rejectReason != null && t.rejectReason.startsWith("total-sites="),
            "mutant totalSitesMismatch rejected: " + t.rejectReason);
        check(t.bytes == orig, "reject returns original array");
        checks++;
        // positive discrimination: pinning the SECOND site's slot must weave
        // THAT site only (bytes differ from the pinned-site weave).
        Weave.Plan p = Weave.collect(
            new Weave.Config("c", "()V", LIST, "()V", -1, -1, MATCHLIST), orig);
        int other = p.sites.get(1).astoreSlot;
        Weave.Result w2 = Weave.weaveChecked(fixtureCfg(other), orig);
        check(w2.rejectReason == null, "second-slot pin accepted");
        List<String> d = InsnDiff.diff(orig, w2.bytes);
        check(d.size() == 2 && d.get(0).startsWith("c()V#" + p.sites.get(1).newIndex + ":"),
            "second-site weave retargets its own site: " + d);
    }

    static void reject(String name, byte[] mutant, String reasonPrefix,
            byte[] orig, int pinned) {
        Weave.Result r = Weave.weaveChecked(fixtureCfg(pinned), mutant);
        checks++;
        if (r.rejectReason == null || !r.rejectReason.startsWith(reasonPrefix))
            throw new AssertionError("FAIL mutant " + name
                + " expected '" + reasonPrefix + "' got '" + r.rejectReason + "'");
        if (r.bytes != mutant)
            throw new AssertionError("FAIL mutant " + name + " must return original array");
    }
}
