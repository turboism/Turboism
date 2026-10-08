package dev.turboism.validation.dmatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Own shadow fixture mirroring the bytecode-verified slice of 5303
 * triangulation.h.c() Phase-3 (the "match window", bci 223-522). Own classes
 * only - no official bytecode is loaded or executed. This is a BEHAVIOURAL
 * slice, not a host-type-equivalence claim.
 *
 * Bytecode-verified facts (javap on reviewed Live2D_Cubism.jar 5.3.03,
 * jar sha256 bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166):
 *  - window: matchList = new ArrayList (bci 223); outer it = k.c() (233);
 *    inner it = tl.iterator() fresh per edge (268); per triangle builds
 *    eAB=j(a,b) (301-318), eBC=j(b,c) (320-337), eCA=j(c,a) (339-356);
 *    then flags fX = (r.a(ej,eX) != null) (358-416); then per candidate
 *    in order: if (fX && !h.a(ej,eX) && !matchList.contains(eX)) add(eX)
 *    (418-520). No k mutation, no TriangleList mutation, no iterator.remove
 *    anywhere inside 223-522 (verified: only k.c() invocations in the whole
 *    c() method are at bci 84 and 233; the sole collection writes in the
 *    window are matchList.add at 448/482/516).
 *  - j declares NEITHER equals NOR hashCode (javap -p: constructor, a, b, c,
 *    a(int), toString, a(j,Z) only) -> Object identity semantics. Therefore
 *    matchList.contains(fresh) can only hit on an identical reference, and
 *    since every candidate is new within the same iteration it is a dead
 *    check in practice; semantics remain "identity membership".
 *  - h.a(j,j)Z (private): undirected shared-endpoint-INDEX predicate:
 *    any of a1.a==a2.a / a1.a==a2.b / a1.b==a2.a / a1.b==a2.b on getIndex()
 *    ints -> true. No null checks (invokevirtual on arg would NPE).
 *  - r.a(j,j): segment-segment intersection; checkNotNullParameter x2;
 *    delegates to a(GVector2 x4) parametric t/u test -> GVector2 iff
 *    0<=t<=1 && 0<=u<=1 else null (NaN -> null via fcmpg).
 *  - j.<init>: checkNotNullParameter x2; assert(a.index != b.index) guarded
 *    by kotlin._Assertions.ENABLED (false unless -ea).
 *  - next() results guarded by Intrinsics.checkNotNullExpressionValue(x,"")
 *    -> NPE " must not be null" (the ldc #4 string is EMPTY in 5.3.03).
 *  - TriPoint extends GVector2; equals compares x,y ONLY (index ignored)
 *    while hashCode = 31*(31*index + h(x)) + h(y) - contract violation,
 *    not exercised by this window (no TriPoint.equals call in 223-522).
 *  - l.hashCode = constant 0; l.equals = cyclic vertex-permutation
 *    TriPoint-equality (6 orderings). Not exercised by this window either
 *    (the LinkedHashSet is an input; only its iterator is used).
 *
 * The Observer seam is fixture instrumentation: selfcheck records events
 * through it, the benchmark passes Observer.NOOP. It does not change
 * results - every observable value still comes from the real calls below.
 */
public final class Shadow {
    private Shadow() {}

    /** kotlin._Assertions.ENABLED is false unless -ea; kept as a constant so
     *  the shadow constructors mirror the official guard faithfully. */
    static final boolean ASSERTIONS_ENABLED = false;

    /** Lightweight event tap inside the match window. Default is off. */
    public interface Observer {
        Observer NOOP = new Observer() {};
        /** Fired after next()+checkNotNullExpressionValue of the k iterator. */
        default void edgeNext(JEdge edge) {}
        /** Fired after next()+checkNotNullExpressionValue of the triangle iterator. */
        default void triNext(ShadowL tri) {}
        /** Fired after constructing candidate i (0=AB,1=BC,2=CA). */
        default void candidate(int which, JEdge cand) {}
        /** Fired with the r.a intersection outcome (null/non-null -> boolean). */
        default void flag(int which, boolean intersects) {}
        /** Fired with the h.a(ej,cand) shared-endpoint outcome. Only when flag. */
        default void share(int which, boolean shared) {}
        /** Fired with the membership query outcome. Only when flag && !shared. */
        default void query(int which, boolean contained) {}
        /** Fired with the real matchList.add result. Only when !contained. */
        default void append(int which, JEdge cand) {}
    }

    /** j-family view needed by the window: two TriPoint endpoints. */
    public interface JEdge {
        ShadowTriPoint a();
        ShadowTriPoint b();
    }

    /** Factory for fresh candidate edges; lets domains swap j flavours. */
    public interface JFactory {
        JEdge make(ShadowTriPoint a, ShadowTriPoint b);
        JFactory DEFAULT = new JFactory() {
            public JEdge make(ShadowTriPoint a, ShadowTriPoint b) { return new ShadowJ(a, b); }
        };
    }

    /** GVector2 analog: two float coordinates. */
    public static class ShadowGVector2 {
        private final float x, y;
        public ShadowGVector2(float x, float y) { this.x = x; this.y = y; }
        public float getX() { return x; }
        public float getY() { return y; }
    }

    /** TriPoint analog: GVector2 + index. equals/hashCode replicate the
     *  official contract violation (equals on x,y only; index inside hash).
     *  Neither is invoked inside the window - included for fidelity only. */
    public static class ShadowTriPoint extends ShadowGVector2 {
        private final int index;
        public ShadowTriPoint(float x, float y, int index) {
            super(x, y);
            this.index = index;
        }
        public int getIndex() { return index; }
        @Override public int hashCode() {
            return index * 31 * 31 + Float.hashCode(getX()) * 31 + Float.hashCode(getY());
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ShadowTriPoint)) return false;
            ShadowTriPoint t = (ShadowTriPoint) o;
            return Float.compare(getX(), t.getX()) == 0 && Float.compare(getY(), t.getY()) == 0;
        }
    }

    /** Primary edge analog (j). NO equals/hashCode - Object identity, exactly
     *  as javap shows for official j. */
    public static class ShadowJ implements JEdge {
        private final ShadowTriPoint a, b;
        public ShadowJ(ShadowTriPoint a, ShadowTriPoint b) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(a, "a");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(b, "b");
            this.a = a; this.b = b;
            boolean distinct = a.getIndex() != b.getIndex();
            if (ASSERTIONS_ENABLED && !distinct) throw new AssertionError("Assertion failed");
        }
        public ShadowTriPoint a() { return a; }
        public ShadowTriPoint b() { return b; }
    }

    /** Negative-control j flavour: undirected endpoint-INDEX pair equals with a
     *  CONSISTENT hashCode (value semantics done right). */
    public static class ShadowJValEq implements JEdge {
        private final ShadowTriPoint a, b;
        public ShadowJValEq(ShadowTriPoint a, ShadowTriPoint b) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(a, "a");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(b, "b");
            this.a = a; this.b = b;
        }
        public ShadowTriPoint a() { return a; }
        public ShadowTriPoint b() { return b; }
        private long key() {
            int i0 = a.getIndex(), i1 = b.getIndex();
            int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
            return ((long) lo << 32) | (hi & 0xffffffffL);
        }
        @Override public boolean equals(Object o) {
            return (o instanceof ShadowJValEq) && ((ShadowJValEq) o).key() == key();
        }
        @Override public int hashCode() { return Long.hashCode(key()); }
    }

    /** Negative-control j flavour: undirected endpoint-INDEX pair equals but
     *  hashCode NOT overridden (identity) - the equals/hashCode-inconsistent
     *  case that silently breaks any HashSet mirror of contains. */
    public static class ShadowJBrokenHash implements JEdge {
        private final ShadowTriPoint a, b;
        public ShadowJBrokenHash(ShadowTriPoint a, ShadowTriPoint b) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(a, "a");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(b, "b");
            this.a = a; this.b = b;
        }
        public ShadowTriPoint a() { return a; }
        public ShadowTriPoint b() { return b; }
        private long key() {
            int i0 = a.getIndex(), i1 = b.getIndex();
            int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
            return ((long) lo << 32) | (hi & 0xffffffffL);
        }
        @Override public boolean equals(Object o) {
            return (o instanceof ShadowJBrokenHash) && ((ShadowJBrokenHash) o).key() == key();
        }
        // no hashCode override - inconsistent with equals on purpose
    }

    /** Triangle analog (l): three TriPoint fields + cached edges d/e/f like the
     *  official kotlin.Lazy edges (d=j(a,b), e=j(b,c), f=j(c,a)). Getter fault
     *  knobs are a FIXTURE-LEVEL stressor only - official a()/b()/c() are plain
     *  field getters that cannot throw. */
    public static class ShadowL {
        private final ShadowTriPoint a, b, c;
        private final int faultAt;                  // 0=none, 1=a, 2=b, 3=c
        private final RuntimeException fault;
        private ShadowJ d, e, f;
        public ShadowL(ShadowTriPoint a, ShadowTriPoint b, ShadowTriPoint c) {
            this(a, b, c, 0, null);
        }
        public ShadowL(ShadowTriPoint a, ShadowTriPoint b, ShadowTriPoint c,
                       int faultAt, RuntimeException fault) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(a, "a");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(b, "b");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(c, "c");
            this.a = a; this.b = b; this.c = c;
            this.faultAt = faultAt; this.fault = fault;
            boolean ok = a.getIndex() != b.getIndex() && a.getIndex() != c.getIndex();
            if (ASSERTIONS_ENABLED && !ok) throw new AssertionError("Assertion failed");
        }
        private void maybeThrow(int which) {
            if (which == faultAt && fault != null) throw fault;
        }
        public ShadowTriPoint a() { maybeThrow(1); return a; }
        public ShadowTriPoint b() { maybeThrow(2); return b; }
        public ShadowTriPoint c() { maybeThrow(3); return c; }
        public ShadowJ d() { if (d == null) d = new ShadowJ(a(), b()); return d; }
        public ShadowJ e() { if (e == null) e = new ShadowJ(b(), c()); return e; }
        public ShadowJ f() { if (f == null) f = new ShadowJ(c(), a()); return f; }
    }

    /** Faithful port of k: live ArrayList, identical method set. */
    public static class ShadowK {
        private final ArrayList<JEdge> a = new ArrayList<>();
        public ArrayList<JEdge> a() { return a; }
        public int b() { return a.size(); }
        public Iterator<JEdge> c() {
            Iterator<JEdge> it = a.iterator();
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(it, "");
            return it;
        }
        public boolean a(JEdge j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            return a.add(j);
        }
        public boolean b(JEdge j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            return a.remove(j);
        }
        /** k.a(j,Z): directed when directed==true, else undirected; index-pair
         *  scan exactly as javap (not used inside the Phase-3 window). */
        public boolean a(JEdge j, boolean directed) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            for (JEdge e : a) {
                if (directed) {
                    if (e.a().getIndex() == j.a().getIndex()
                            && e.b().getIndex() == j.b().getIndex()) return true;
                } else {
                    if ((e.a().getIndex() == j.a().getIndex()
                            && e.b().getIndex() == j.b().getIndex())
                        || (e.a().getIndex() == j.b().getIndex()
                            && e.b().getIndex() == j.a().getIndex())) return true;
                }
            }
            return false;
        }
    }

    /** TriangleList analog: declared LinkedHashSet, iterator() = b.iterator().
     *  The field is package-private only so the fixture can inject a null set
     *  element for the null-element domain (host would need raw access too). */
    public static class ShadowTriangleList implements Iterable<ShadowL> {
        final LinkedHashSet<ShadowL> b = new LinkedHashSet<>();
        public Iterator<ShadowL> iterator() {
            Iterator<ShadowL> it = b.iterator();
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(it, "");
            return it;
        }
        public boolean a(ShadowL l) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(l, "triangle");
            return b.add(l);
        }
        public boolean b(ShadowL l) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(l, "triangle");
            return b.remove(l);
        }
        public boolean c(ShadowL l) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(l, "triangle");
            return b.contains(l);
        }
        public int a() { return b.size(); }
    }

    /** r singleton analog: segment-segment intersection, exact float port. */
    public static final class ShadowR {
        public static final ShadowR a = new ShadowR();
        private ShadowR() {}
        /** r.a(j,j): NPE-guarded; endpoints are TriPoints (IS-A GVector2). */
        public ShadowGVector2 a(JEdge e1, JEdge e2) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(e1, "e1");
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(e2, "e2");
            return a((ShadowGVector2) e1.a(), (ShadowGVector2) e1.b(),
                     (ShadowGVector2) e2.a(), (ShadowGVector2) e2.b());
        }
        /** r.a(g,g,g,g): parametric segment intersection. Returns the crossing
         *  point iff t,u both in [0,1], else null. NaN -> null (fcmpg). */
        public ShadowGVector2 a(ShadowGVector2 p1, ShadowGVector2 p2,
                                ShadowGVector2 p3, ShadowGVector2 p4) {
            float f5 = (p4.getY() - p3.getY()) * (p4.getX() - p1.getX())
                     - (p4.getX() - p3.getX()) * (p4.getY() - p1.getY());
            float f6 = (p2.getX() - p1.getX()) * (p4.getY() - p1.getY())
                     - (p2.getY() - p1.getY()) * (p4.getX() - p1.getX());
            float f7 = (p2.getX() - p1.getX()) * (p4.getY() - p3.getY())
                     - (p2.getY() - p1.getY()) * (p4.getX() - p3.getX());
            float t = f5 / f7;
            float u = f6 / f7;
            if (t >= 0f && t <= 1f && u >= 0f && u <= 1f) {
                return new ShadowGVector2(p1.getX() + t * (p2.getX() - p1.getX()),
                                          p1.getY() + t * (p2.getY() - p1.getY()));
            }
            return null;
        }
    }

    /** h.a(j,j)Z private fragment: undirected shared-endpoint-INDEX predicate.
     *  No null guards - a null arg dies at the first invokevirtual, as in the
     *  official code. */
    public static boolean shareAnyEndpoint(JEdge x, JEdge y) {
        return x.a().getIndex() == y.a().getIndex()
            || x.a().getIndex() == y.b().getIndex()
            || x.b().getIndex() == y.a().getIndex()
            || x.b().getIndex() == y.b().getIndex();
    }

    /** Reference: h.c() Phase-3 with the real ArrayList.contains identity
     *  scan, instruction-for-instruction. */
    public static ArrayList<JEdge> matchRef(ShadowK k, ShadowTriangleList tl,
                                            JFactory factory, Observer obs) {
        if (k == null) return null;               // host: c.b()==null -> early return (bci 58-63)
        ArrayList<JEdge> match = new ArrayList<>();
        for (Iterator<JEdge> eit = k.c(); eit.hasNext(); ) {
            JEdge ej = eit.next();
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(ej, "");
            obs.edgeNext(ej);
            for (Iterator<ShadowL> tit = tl.iterator(); tit.hasNext(); ) {
                ShadowL l = tit.next();
                kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(l, "");
                obs.triNext(l);
                JEdge eAB = factory.make(l.a(), l.b()); obs.candidate(0, eAB);
                JEdge eBC = factory.make(l.b(), l.c()); obs.candidate(1, eBC);
                JEdge eCA = factory.make(l.c(), l.a()); obs.candidate(2, eCA);
                boolean fAB = ShadowR.a.a(ej, eAB) != null; obs.flag(0, fAB);
                boolean fBC = ShadowR.a.a(ej, eBC) != null; obs.flag(1, fBC);
                boolean fCA = ShadowR.a.a(ej, eCA) != null; obs.flag(2, fCA);
                if (fAB) {
                    boolean s = shareAnyEndpoint(ej, eAB); obs.share(0, s);
                    if (!s) {
                        boolean q = match.contains(eAB); obs.query(0, q);
                        if (!q) { match.add(eAB); obs.append(0, eAB); }
                    }
                }
                if (fBC) {
                    boolean s = shareAnyEndpoint(ej, eBC); obs.share(1, s);
                    if (!s) {
                        boolean q = match.contains(eBC); obs.query(1, q);
                        if (!q) { match.add(eBC); obs.append(1, eBC); }
                    }
                }
                if (fCA) {
                    boolean s = shareAnyEndpoint(ej, eCA); obs.share(2, s);
                    if (!s) {
                        boolean q = match.contains(eCA); obs.query(2, q);
                        if (!q) { match.add(eCA); obs.append(2, eCA); }
                    }
                }
            }
        }
        return match;
    }

    /** Candidate: identical window except the membership check is answered by
     *  a local IdentityHashMap-backed set (O(1)). Key = the object reference
     *  itself, because javap shows j's equality IS identity - the set is a
     *  faithful mirror of ArrayList.contains for ANY contents, not merely
     *  under the fresh-candidate invariant. The index is a local, unreachable
     *  after return; matchList.add still performs the real append. */
    public static ArrayList<JEdge> matchCand(ShadowK k, ShadowTriangleList tl,
                                             JFactory factory, Observer obs) {
        if (k == null) return null;
        ArrayList<JEdge> match = new ArrayList<>();
        Set<JEdge> seen = Collections.newSetFromMap(new IdentityHashMap<JEdge, Boolean>());
        for (Iterator<JEdge> eit = k.c(); eit.hasNext(); ) {
            JEdge ej = eit.next();
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(ej, "");
            obs.edgeNext(ej);
            for (Iterator<ShadowL> tit = tl.iterator(); tit.hasNext(); ) {
                ShadowL l = tit.next();
                kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(l, "");
                obs.triNext(l);
                JEdge eAB = factory.make(l.a(), l.b()); obs.candidate(0, eAB);
                JEdge eBC = factory.make(l.b(), l.c()); obs.candidate(1, eBC);
                JEdge eCA = factory.make(l.c(), l.a()); obs.candidate(2, eCA);
                boolean fAB = ShadowR.a.a(ej, eAB) != null; obs.flag(0, fAB);
                boolean fBC = ShadowR.a.a(ej, eBC) != null; obs.flag(1, fBC);
                boolean fCA = ShadowR.a.a(ej, eCA) != null; obs.flag(2, fCA);
                if (fAB) {
                    boolean s = shareAnyEndpoint(ej, eAB); obs.share(0, s);
                    if (!s) {
                        boolean q = seen.contains(eAB); obs.query(0, q);
                        if (!q) { match.add(eAB); seen.add(eAB); obs.append(0, eAB); }
                    }
                }
                if (fBC) {
                    boolean s = shareAnyEndpoint(ej, eBC); obs.share(1, s);
                    if (!s) {
                        boolean q = seen.contains(eBC); obs.query(1, q);
                        if (!q) { match.add(eBC); seen.add(eBC); obs.append(1, eBC); }
                    }
                }
                if (fCA) {
                    boolean s = shareAnyEndpoint(ej, eCA); obs.share(2, s);
                    if (!s) {
                        boolean q = seen.contains(eCA); obs.query(2, q);
                        if (!q) { match.add(eCA); seen.add(eCA); obs.append(2, eCA); }
                    }
                }
            }
        }
        return match;
    }
}
