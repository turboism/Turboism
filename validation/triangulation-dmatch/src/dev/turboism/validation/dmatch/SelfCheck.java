package dev.turboism.validation.dmatch;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.turboism.validation.dmatch.Shadow.JEdge;
import dev.turboism.validation.dmatch.Shadow.JFactory;
import dev.turboism.validation.dmatch.Shadow.Observer;
import dev.turboism.validation.dmatch.Shadow.ShadowJ;
import dev.turboism.validation.dmatch.Shadow.ShadowJBrokenHash;
import dev.turboism.validation.dmatch.Shadow.ShadowJValEq;
import dev.turboism.validation.dmatch.Shadow.ShadowK;
import dev.turboism.validation.dmatch.Shadow.ShadowL;
import dev.turboism.validation.dmatch.Shadow.ShadowTriPoint;
import dev.turboism.validation.dmatch.Shadow.ShadowTriangleList;

/**
 * Event-by-event differential driver: runs reference and candidate on the
 * SAME input references (the window mutates neither k nor the triangle set,
 * so sharing is faithful) and compares observable behaviour exactly:
 * event stream (identity-normalised), final match list (size, per-slot
 * endpoint index/coords, identity-ordinal pattern), thrown exception
 * class+message (only the intrinsic caller-method segment is normalised,
 * because ref/cand call sites legitimately differ - KBUILD precedent).
 *
 * Negative controls are deliberately-broken candidates plus j variants whose
 * equals/hashCode diverge from verified identity semantics; every one must be
 * REJECTED by the comparator on its discriminating domain.
 */
public final class SelfCheck {
    private static int checks = 0;
    private static void ok(boolean cond, String name) {
        checks++;
        if (!cond) throw new AssertionError("CHECK FAILED: " + name);
    }

    /* ---------------- event recording ---------------- */

    /** Observer -> event list. Object identity is normalised to
     *  first-appearance ordinals so two runs producing structurally identical
     *  object graphs compare equal while any identity/shape drift differs. */
    static final class Recorder implements Observer {
        final List<String> events = new ArrayList<>();
        private final Map<Object, Integer> ids = new IdentityHashMap<>();
        private int next = 0;
        int id(Object o) {
            Integer v = ids.get(o);
            if (v == null) { v = next++; ids.put(o, v); }
            return v;
        }
        public void edgeNext(JEdge e)        { events.add("edge#" + id(e)); }
        public void triNext(ShadowL t)       { events.add("tri#" + id(t)); }
        public void candidate(int i, JEdge c){ events.add("cand" + i + "#" + id(c)
                                                       + "(a=" + id(c.a()) + ",b=" + id(c.b()) + ")"); }
        public void flag(int i, boolean v)   { events.add("flag" + i + "=" + v); }
        public void share(int i, boolean v)  { events.add("share" + i + "=" + v); }
        public void query(int i, boolean v)  { events.add("query" + i + "=" + v); }
        public void append(int i, JEdge c)   { events.add("append" + i + "#" + id(c)); }
    }

    /** One window invocation's observable outcome. */
    static final class Outcome {
        List<String> events;
        ArrayList<JEdge> result;          // null only when k==null early-return
        Throwable thrown;
        String resultDigest;              // structural digest, identity-normalised
    }

    interface Impl {
        ArrayList<JEdge> run(ShadowK k, ShadowTriangleList tl, JFactory f, Observer o);
    }

    static Outcome run(Impl impl, ShadowK k, ShadowTriangleList tl, JFactory f) {
        Outcome out = new Outcome();
        Recorder rec = new Recorder();
        try {
            out.result = impl.run(k, tl, f, rec);
        } catch (Throwable t) {
            out.thrown = t;
        }
        out.events = rec.events;
        out.resultDigest = digest(out.result, rec);
        return out;
    }

    /** Structural digest of the result list: per-slot endpoint index+float
     *  bits plus identity ordinals (shared ordinals expose aliasing shape). */
    static String digest(ArrayList<JEdge> result, Recorder rec) {
        if (result == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (JEdge e : result) {
            sb.append('#').append(rec.id(e)).append('(')
              .append(e.a().getIndex()).append(',').append(e.b().getIndex()).append(';')
              .append(Float.floatToIntBits(e.a().getX())).append(',').append(Float.floatToIntBits(e.a().getY())).append(';')
              .append(Float.floatToIntBits(e.b().getX())).append(',').append(Float.floatToIntBits(e.b().getY())).append(';')
              .append(rec.id(e.a())).append(',').append(rec.id(e.b())).append(')');
        }
        return sb.append(']').toString();
    }

    /** Narrow normalisation (KBUILD precedent): only the caller-method
     *  segment of the kotlin intrinsic parameter-NPE message, since ref/cand
     *  throw from differently-named methods. Everything else byte-exact. */
    static String normMsg(String m) {
        if (m == null) return null;
        return m.replaceAll(
            "Parameter specified as non-null is null: method [^,]+, parameter .*",
            "Parameter specified as non-null is null: method <M>, parameter <P>");
    }

    /** Null-safe describe of an outcome difference, "" when identical. */
    static String diff(Outcome a, Outcome b) {
        if (a.thrown == null != (b.thrown == null))
            return "thrown presence " + cls(a.thrown) + " vs " + cls(b.thrown);
        if (a.thrown != null) {
            if (!a.thrown.getClass().equals(b.thrown.getClass()))
                return "thrown class " + cls(a.thrown) + " vs " + cls(b.thrown);
            String ma = normMsg(a.thrown.getMessage()), mb = normMsg(b.thrown.getMessage());
            if (ma == null ? mb != null : !ma.equals(mb))
                return "thrown message <" + ma + "> vs <" + mb + ">";
        }
        if (!a.events.equals(b.events)) {
            int n = Math.min(a.events.size(), b.events.size());
            for (int i = 0; i < n; i++)
                if (!a.events.get(i).equals(b.events.get(i)))
                    return "event[" + i + "] " + a.events.get(i) + " vs " + b.events.get(i);
            return "event count " + a.events.size() + " vs " + b.events.size();
        }
        if (!a.resultDigest.equals(b.resultDigest))
            return "result " + a.resultDigest + " vs " + b.resultDigest;
        return "";
    }

    private static String cls(Throwable t) { return t == null ? "none" : t.getClass().getName(); }

    /* ---------------- input builders ---------------- */

    static ShadowTriPoint tp(float x, float y, int i) { return new ShadowTriPoint(x, y, i); }

    static ShadowK kOf(JEdge... edges) {
        ShadowK k = new ShadowK();
        for (JEdge e : edges) k.a().add(e);
        return k;
    }

    static ShadowTriangleList tlOf(ShadowL... tris) {
        ShadowTriangleList tl = new ShadowTriangleList();
        for (ShadowL l : tris) tl.a(l);
        return tl;
    }

    static ShadowJ j(ShadowTriPoint a, ShadowTriPoint b) { return new ShadowJ(a, b); }

    /* ---------------- domains ---------------- */

    /** Differential harness: ref vs cand must be indistinguishable. */
    static void expectEqual(String name, ShadowK k, ShadowTriangleList tl, JFactory f) {
        Outcome r = run(Shadow::matchRef, k, tl, f);
        Outcome c = run(Shadow::matchCand, k, tl, f);
        String d = diff(r, c);
        ok(d.isEmpty(), "domain " + name + ": " + d);
    }

    /** A deliberately-broken impl must be REJECTED on this domain. */
    static void expectReject(String name, Impl bad, ShadowK k, ShadowTriangleList tl, JFactory f) {
        Outcome r = run(Shadow::matchRef, k, tl, f);
        Outcome b = run(bad, k, tl, f);
        String d = diff(r, b);
        ok(!d.isEmpty(), "negative " + name + " not rejected (matched reference)");
    }

    /** Broken-vs-candidate discrimination on variant j semantics: ref and
     *  cand are EXPECTED to diverge under non-identity equals - the check is
     *  that the harness observes it (candidate validity bound proof). */
    static void expectVariantDiverges(String name, ShadowK k, ShadowTriangleList tl, JFactory f) {
        Outcome r = run(Shadow::matchRef, k, tl, f);
        Outcome c = run(Shadow::matchCand, k, tl, f);
        String d = diff(r, c);
        ok(!d.isEmpty(), "variant domain " + name + ": expected divergence, got parity");
    }

    public static void main(String[] args) {
        /* ===== geometry helpers ===== */
        // A "plus" arrangement: edge ej = horizontal segment (-1,0)-(3,0);
        // triangle with a vertical edge (1,-1)-(1,1) that crosses ej's interior
        // and shares no endpoint index.
        ShadowTriPoint P = tp(-1f, 0f, 10), Q = tp(3f, 0f, 11);
        ShadowJ ejCross = j(P, Q);
        ShadowTriPoint A = tp(1f, -1f, 20), B = tp(1f, 1f, 21), C = tp(4f, 1f, 22);
        ShadowL tri1 = new ShadowL(A, B, C);            // edge AB crosses ej
        // second triangle sharing endpoint VALUES of A,B via fresh TriPoints
        ShadowTriPoint A2 = tp(1f, -1f, 20), B2 = tp(1f, 1f, 21), D = tp(-2f, 3f, 23);
        ShadowL tri2 = new ShadowL(A2, B2, D);          // duplicate AB edge, distinct objects

        /* ===== primary domains (ref vs cand must match) ===== */

        // empty inputs
        expectEqual("emptyK", kOf(), tlOf(tri1), JFactory.DEFAULT);
        expectEqual("emptyTL", kOf(ejCross), tlOf(), JFactory.DEFAULT);
        expectEqual("bothEmpty", kOf(), tlOf(), JFactory.DEFAULT);

        // basic crossing: ej crosses tri1.AB; also CA may/may not cross
        expectEqual("cross1x1", kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);

        // contains-is-dead load-bearing: two triangles yielding endpoint-equal
        // candidate edges (distinct objects) must BOTH append
        expectEqual("dupEdgeObj", kOf(ejCross), tlOf(tri1, tri2), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCross), tlOf(tri1, tri2), JFactory.DEFAULT);
            long abAppends = r.events.stream().filter(e -> e.startsWith("append0")).count();
            ok(abAppends == 2, "ref must append both equal-endpoint candidates, got " + abAppends);
            ok(r.result != null && r.result.size() >= 2 && r.result.get(0) != r.result.get(1),
               "duplicate appends must be distinct objects");
        }

        // shared endpoint: ej shares index 20 with candidate AB of a triangle
        ShadowJ ejShare = j(tp(0f, 0.5f, 20), tp(3f, 0.5f, 30));
        expectEqual("shareEndpoint", kOf(ejShare), tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejShare), tlOf(tri1), JFactory.DEFAULT);
            ok(r.events.stream().anyMatch(e -> e.equals("share0=true")),
               "share-check must fire true for endpoint-sharing candidate");
            ok(r.events.stream().noneMatch(e -> e.startsWith("append0")),
               "shared-endpoint candidate must not append");
        }

        // cross-endpoint sharing only: ej.b == cand.a (a1.b == a2.a case)
        ShadowJ ejCrossShare = j(tp(0f, 0.5f, 30), tp(3f, 0.5f, 21)); // ej.b.idx == B.idx(21)
        expectEqual("crossShare", kOf(ejCrossShare), tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCrossShare), tlOf(tri1), JFactory.DEFAULT);
            ok(r.events.stream().anyMatch(e -> e.equals("share0=true")),
               "undirected share-check must catch cross-endpoint sharing");
        }

        // int extremes incl. negatives & MIN/MAX (indices only - geometry small)
        ShadowTriPoint X1 = tp(0f, 0f, Integer.MIN_VALUE), X2 = tp(2f, 0f, -1);
        ShadowJ ejExt = j(X1, X2);
        ShadowL triExt = new ShadowL(tp(1f, -1f, Integer.MAX_VALUE),
                                     tp(1f, 1f, Integer.MIN_VALUE + 1), tp(3f, 3f, 0));
        expectEqual("intExtremes", kOf(ejExt), tlOf(triExt), JFactory.DEFAULT);

        // same index different coords (share-check is index-only)
        ShadowJ ejDupIdx = j(tp(1f, -0.5f, 20), tp(5f, 0f, 40));
        expectEqual("sameIdxDiffCoords", kOf(ejDupIdx), tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejDupIdx), tlOf(tri1), JFactory.DEFAULT);
            ok(r.events.stream().anyMatch(e -> e.equals("share0=true")),
               "same-index different-coords must share-skip (index semantics)");
        }

        // same coords different index: NOT a share (index differs) -> may append
        ShadowJ ejSameXY = j(tp(1f, -1f, 99), tp(5f, 5f, 98)); // a=(1,-1) same xy as A but idx 99
        expectEqual("sameXYDiffIdx", kOf(ejSameXY), tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejSameXY), tlOf(tri1), JFactory.DEFAULT);
            ok(r.events.stream().anyMatch(e -> e.equals("share0=false")),
               "same-coords different-index must NOT share-skip (index semantics)");
            ok(r.events.stream().anyMatch(e -> e.startsWith("append0")),
               "geometric endpoint contact with different index must still append");
        }

        // degenerate edge (same index both ends; assertions disabled)
        ShadowJ ejDegen = j(tp(0f, 0f, 50), tp(1f, 1f, 50));
        expectEqual("degenerateEdge", kOf(ejDegen), tlOf(tri1), JFactory.DEFAULT);
        ShadowL triDegen = new ShadowL(tp(0f, 0f, 51), tp(2f, 0f, 51), tp(1f, 2f, 52));
        expectEqual("degenerateTri", kOf(ejCross), tlOf(triDegen), JFactory.DEFAULT);

        // null domains
        ShadowK kNull = kOf(ejCross); kNull.a().add(null);
        expectEqual("nullEdgeElem", kNull, tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kNull, tlOf(tri1), JFactory.DEFAULT);
            ok(r.thrown instanceof NullPointerException
               && " must not be null".equals(r.thrown.getMessage()),
               "null edge element must NPE ' must not be null', got " + r.thrown);
        }
        expectEqual("nullTriElem", kOf(ejCross), rawTl(tri1, null), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCross), rawTl(tri1, null), JFactory.DEFAULT);
            ok(r.thrown instanceof NullPointerException
               && " must not be null".equals(r.thrown.getMessage()),
               "null triangle element must NPE ' must not be null', got " + r.thrown);
        }
        expectEqual("nullTL_nonemptyK", kOf(ejCross), null, JFactory.DEFAULT);
        expectEqual("nullTL_emptyK", kOf(), null, JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(), null, JFactory.DEFAULT);
            ok(r.thrown == null && r.result != null && r.result.isEmpty(),
               "null TL with empty k must not throw (iterator inside edge loop)");
        }
        expectEqual("nullK", null, tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, null, tlOf(tri1), JFactory.DEFAULT);
            ok(r.thrown == null && r.result == null, "null k must early-return null");
        }

        // fixture stressor: getter returning null TriPoint -> j.<init> param NPE
        // (host-unreachable under Kotlin nonnull; fixture-level parity check)
        ShadowL triNullA = new ShadowL(A, B, C) {
            @Override public ShadowTriPoint a() { return null; }
        };
        expectEqual("nullTriPointGetter", kOf(ejCross), tlOf(triNullA), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCross), tlOf(triNullA), JFactory.DEFAULT);
            ok(r.thrown instanceof NullPointerException
               && r.thrown.getMessage() != null
               && r.thrown.getMessage().startsWith("Parameter specified as non-null is null"),
               "null TriPoint getter must hit j.<init> param NPE, got " + r.thrown);
        }
        ShadowL triFaultA = new ShadowL(A, B, C, 1, new RuntimeException("getter:A"));
        ShadowL triFaultB = new ShadowL(A, B, C, 2, new RuntimeException("getter:B"));
        ShadowL triFaultC = new ShadowL(A, B, C, 3, new RuntimeException("getter:C"));
        expectEqual("faultGetterA", kOf(ejCross), tlOf(triFaultA), JFactory.DEFAULT);
        expectEqual("faultGetterB", kOf(ejCross), tlOf(triFaultB), JFactory.DEFAULT);
        expectEqual("faultGetterC", kOf(ejCross), tlOf(triFaultC), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCross), tlOf(triFaultB), JFactory.DEFAULT);
            ok(r.thrown instanceof RuntimeException && "getter:B".equals(r.thrown.getMessage()),
               "faulting getter must propagate its own RuntimeException");
        }

        // float domains: NaN, Inf, -0.0, collinear-overlap, touching endpoints
        ShadowJ ejNaN = j(tp(Float.NaN, 0f, 60), tp(1f, 1f, 61));
        expectEqual("nanCoords", kOf(ejNaN), tlOf(tri1), JFactory.DEFAULT);
        ShadowJ ejInf = j(tp(Float.NEGATIVE_INFINITY, 0f, 62), tp(Float.POSITIVE_INFINITY, 0f, 63));
        expectEqual("infCoords", kOf(ejInf), tlOf(tri1), JFactory.DEFAULT);
        ShadowJ ejNegZero = j(tp(-0.0f, 0.0f, 64), tp(0.0f, -0.0f, 65));
        expectEqual("negZero", kOf(ejNegZero), tlOf(tri1), JFactory.DEFAULT);
        // collinear overlap: ej (0,0)-(4,0), candidate edge (1,0)-(2,0) on same line
        ShadowTriPoint C1 = tp(1f, 0f, 70), C2 = tp(2f, 0f, 71), C3 = tp(1.5f, 0f, 72);
        ShadowL triCollinear = new ShadowL(C1, C2, C3);
        expectEqual("collinear", kOf(ejCross), tlOf(triCollinear), JFactory.DEFAULT);
        // endpoint-touch (t or u exactly 0/1) without index sharing
        ShadowJ ejTouch = j(tp(1f, 1f, 80), tp(6f, 0f, 81)); // touches B's point, different index
        expectEqual("touch", kOf(ejTouch), tlOf(tri1), JFactory.DEFAULT);
        {
            Outcome r = run(Shadow::matchRef, kOf(ejTouch), tlOf(tri1), JFactory.DEFAULT);
            ok(r.events.stream().anyMatch(e -> e.equals("flag0=true")),
               "endpoint contact must still flag (closed [0,1] param range)");
            ok(r.events.stream().anyMatch(e -> e.equals("share0=false")),
               "endpoint contact with different index must not share-skip");
            ok(r.events.stream().anyMatch(e -> e.startsWith("append0")),
               "endpoint contact candidate must append");
        }

        // larger mixed domain: several edges x several triangles, dup candidates
        ShadowK big = kOf(ejCross, ejShare, ejCrossShare, ejDegen, ejTouch);
        ShadowTriangleList bigTl = tlOf(tri1, tri2, triCollinear, triExt, triDegen);
        expectEqual("mixed", big, bigTl, JFactory.DEFAULT);

        /* ===== variant j domains: equals/hashCode non-identity ===== */
        // Under value-equals j the reference DOES dedupe equal-endpoint
        // candidates; the identity-index candidate must be observed diverging.
        JFactory valF = new JFactory() {
            public JEdge make(ShadowTriPoint a, ShadowTriPoint b) { return new ShadowJValEq(a, b); }
        };
        ShadowK kVal = kOf(new ShadowJValEq(P, Q));
        expectVariantDiverges("valEq", kVal, tlOf(tri1, tri2), valF);
        {
            Outcome r = run(Shadow::matchRef, kVal, tlOf(tri1, tri2), valF);
            ok(r.events.stream().anyMatch(e -> e.equals("query0=true")),
               "under value-equals the reference contains must dedupe the second equal candidate");
        }
        JFactory brokenF = new JFactory() {
            public JEdge make(ShadowTriPoint a, ShadowTriPoint b) { return new ShadowJBrokenHash(a, b); }
        };
        ShadowK kBroken = kOf(new ShadowJBrokenHash(P, Q));
        expectVariantDiverges("brokenHash", kBroken, tlOf(tri1, tri2), brokenF);

        /* ===== negative controls: deliberately-broken impls must be rejected ===== */

        // N1: value-keyed dedup (endpoint index key) - drops legit duplicates
        expectReject("valueDedup",
            (k, tl, f, o) -> Negatives.matchValueDedup(k, tl, f, o),
            kOf(ejCross), tlOf(tri1, tri2), JFactory.DEFAULT);

        // N2: missing share-check
        expectReject("noShare",
            (k, tl, f, o) -> Negatives.matchNoShare(k, tl, f, o),
            kOf(ejShare), tlOf(tri1), JFactory.DEFAULT);

        // N3: directed-only share-check misses cross-endpoint sharing
        expectReject("directedShare",
            (k, tl, f, o) -> Negatives.matchDirectedShare(k, tl, f, o),
            kOf(ejCrossShare), tlOf(tri1), JFactory.DEFAULT);

        // N4: query before share (evaluation-order swap)
        expectReject("swapOrder",
            (k, tl, f, o) -> Negatives.matchSwapOrder(k, tl, f, o),
            kOf(ejShare), tlOf(tri1, tri2), JFactory.DEFAULT);

        // N5: appends a fresh copy instead of the constructed candidate
        expectReject("appendNew",
            (k, tl, f, o) -> Negatives.matchAppendNew(k, tl, f, o),
            kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);

        // N6: reversed candidate endpoints j(b,a)
        expectReject("reversedCand",
            (k, tl, f, o) -> Negatives.matchReversed(k, tl, f, o),
            kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);

        // N7: gate order CA,AB,BC instead of AB,BC,CA
        expectReject("gateOrder",
            (k, tl, f, o) -> Negatives.matchGateOrder(k, tl, f, o),
            kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);

        // N8: HashSet mirror on j objects - unsafe under inconsistent
        // equals/hashCode (rejected on the brokenHash variant domain)
        expectReject("hashSetMirror",
            (k, tl, f, o) -> Negatives.matchHashSetMirror(k, tl, f, o),
            kBroken, tlOf(tri1, tri2), brokenF);

        // N9: comparator sanity - corrupted event stream must be rejected
        {
            Outcome r = run(Shadow::matchRef, kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);
            Outcome c = run(Shadow::matchCand, kOf(ejCross), tlOf(tri1), JFactory.DEFAULT);
            ok(diff(r, c).isEmpty(), "sanity ref==cand on cross1x1");
            c.events = new ArrayList<>(c.events);
            c.events.remove(c.events.size() - 1);
            ok(!diff(r, c).isEmpty(), "truncated event stream must be rejected");
        }

        System.out.println("DMATCH_SELFCHECK PASS checks=" + checks);
    }

    /** Triangle list with a raw null element (bypasses tl.a(l)'s param check
     *  via the package-private set - fixture-level access, documented). */
    static ShadowTriangleList rawTl(ShadowL first, ShadowL secondOrNull) {
        ShadowTriangleList tl = tlOf(first);
        tl.b.add(secondOrNull);
        return tl;
    }
}
