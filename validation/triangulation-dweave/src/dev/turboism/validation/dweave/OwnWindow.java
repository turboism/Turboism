package dev.turboism.validation.dweave;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import kotlin.jvm.internal.Intrinsics;

/**
 * Own window-replica fixture for T029-DWEAVE. Reproduces the javap-verified
 * allocation-point shape of official {@code h.c()V} under own types:
 *
 *  - ONE {@code new ArrayList} Phase-3 allocation (the weave target — the
 *    matchList), followed by the nested-iteration match window
 *    (outer edges iterator x per-edge fresh inner triangle iterator;
 *    per triangle three fresh candidates; per candidate the gate chain
 *    flag -> !share -> !contains -> add), exactly as verified in
 *    triangulation-dmatch/DESIGN.md (bci 223-522).
 *  - a SECOND {@code new ArrayList} site later in the same method (Phase-4
 *    analog: official bci 523-530, astore 8). The weaver must leave it
 *    untouched — the pinned-slot gate is what disambiguates the two sites.
 *  - post-window consumers on the same matchList instance, mirroring the
 *    official callee shapes: isEmpty via the Collection interface
 *    (bci 535-540), remove(0) FIFO drain (h.b analog, bci 567-570) with the
 *    conditional head re-add through matchList.add (bci 806-810). The
 *    official Phase-5 addAll (inside h.a(ArrayList,j,TL,k), bci 189/227)
 *    targets the OTHER list — mirrored here on {@code popped}. None of the
 *    post-window paths calls contains, so any mirror desync is
 *    unobservable — exactly as on the host.
 *
 * Element type Edge is IDENTITY-ONLY (no equals/hashCode) like official j —
 * the IdentityHashMap mirror is the unconditionally-equivalent contains
 * model there (DMATCH verdict).
 *
 * The Observer seam mirrors the dmatch fixture: instrumentation only, no
 * observable value flows through it.
 */
public final class OwnWindow {

    /** Lightweight event tap; default off. */
    public interface Observer {
        Observer NOOP = new Observer() {};
        default void edgeNext(Edge e) {}
        default void triNext(Tri t) {}
        default void candidate(int which, Edge e) {}
        default void flag(int which, boolean v) {}
        default void share(int which, boolean v) {}
        default void query(int which, boolean v) {}
        default void append(int which, Edge e) {}
        default void phase4Pop(Edge e) {}
        default void phase4Readd(Edge e) {}
        default void phase5AddAll(int n) {}
    }

    /** TriPoint analog: two float coords + index. equals/hashCode replicate
     *  the official contract violation (equals on x,y only; index inside
     *  hash) — never invoked inside the window, included for fidelity. */
    public static final class Pt {
        public final float x, y;
        public final int index;
        public Pt(float x, float y, int index) {
            this.x = x; this.y = y; this.index = index;
        }
        @Override public int hashCode() {
            return index * 31 * 31 + Float.hashCode(x) * 31 + Float.hashCode(y);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Pt)) return false;
            Pt t = (Pt) o;
            return Float.compare(x, t.x) == 0 && Float.compare(y, t.y) == 0;
        }
    }

    /** j analog: two Pt endpoints, IDENTITY equality (no equals/hashCode).
     *  Constructor mirrors j.<init>: checkNotNullParameter x2. */
    public static final class Edge {
        public final Pt a, b;
        public Edge(Pt a, Pt b) {
            Intrinsics.checkNotNullParameter(a, "a");
            Intrinsics.checkNotNullParameter(b, "b");
            this.a = a; this.b = b;
        }
    }

    /** l analog: three Pt fields exposed through a()/b()/c(). The
     *  nullGetter knob is a FIXTURE-level stressor (a getter returning null
     *  drives the j.<init> checkNotNullParameter NPE inside the window);
     *  official l getters are plain field reads. */
    public static final class Tri {
        private final Pt a, b, c;
        private final int nullGetter;               // 0=none, 1=a, 2=b, 3=c
        public Tri(Pt a, Pt b, Pt c) { this(a, b, c, 0); }
        private Tri(Pt a, Pt b, Pt c, int nullGetter) {
            Intrinsics.checkNotNullParameter(a, "a");
            Intrinsics.checkNotNullParameter(b, "b");
            Intrinsics.checkNotNullParameter(c, "c");
            this.a = a; this.b = b; this.c = c;
            this.nullGetter = nullGetter;
        }
        public static Tri withNullGetter(int which) {
            return new Tri(new Pt(0, -5, 61), new Pt(10, 5, 62), new Pt(5, -5, 63), which);
        }
        public Pt a() { return nullGetter == 1 ? null : a; }
        public Pt b() { return nullGetter == 2 ? null : b; }
        public Pt c() { return nullGetter == 3 ? null : c; }
    }

    /** r.a(j,j) analog: parametric segment intersection; crossing point iff
     *  t,u both in [0,1] else null; NaN -> null (fcmpg parity). Exact port of
     *  the dmatch-verified shadow. */
    public static Pt intersects(Edge e1, Edge e2) {
        Intrinsics.checkNotNullParameter(e1, "e1");
        Intrinsics.checkNotNullParameter(e2, "e2");
        Pt p1 = e1.a, p2 = e1.b, p3 = e2.a, p4 = e2.b;
        float f5 = (p4.y - p3.y) * (p4.x - p1.x) - (p4.x - p3.x) * (p4.y - p1.y);
        float f6 = (p2.x - p1.x) * (p4.y - p1.y) - (p4.x - p1.x) * (p4.y - p1.y);
        float f7 = (p2.x - p1.x) * (p4.y - p3.y) - (p2.y - p1.y) * (p4.x - p3.x);
        float t = f5 / f7;
        float u = f6 / f7;
        if (t >= 0f && t <= 1f && u >= 0f && u <= 1f) {
            return new Pt(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y), -1);
        }
        return null;
    }

    /** h.a(j,j)Z analog: undirected shared-endpoint-INDEX predicate.
     *  No null guards, as in the official code. */
    public static boolean shareAny(Edge x, Edge y) {
        return x.a.index == y.a.index || x.a.index == y.b.index
            || x.b.index == y.a.index || x.b.index == y.b.index;
    }

    /** h.b(ArrayList) analog: pop head via remove(0), null on empty.
     *  remove(I) is NOT mirrored by MatchList — javap-verified unobservable:
     *  no contains executes on the list after the Phase-3 window. */
    static Edge popHead(ArrayList<Edge> l) { return l.isEmpty() ? null : l.remove(0); }

    /** Collection-interface isEmpty call site (official uses
     *  invokeinterface Collection.isEmpty on the matchList at bci 535-540
     *  and on the Phase-4 list at bci 838-843). */
    static boolean isEmptyC(Collection<?> c) { return c.isEmpty(); }

    /** h.a(ArrayList,j,TL,k) analog: bulk addAll on a list parameter. In the
     *  official callee the addAll target is the PHASE-4 list (aload_1 is the
     *  slot-8 arg at bci 872-879), never the matchList — mirrored here by
     *  calling it on {@code popped}. */
    static int augmentAll(ArrayList<Edge> l, Collection<Edge> extra) {
        boolean changed = l.addAll(extra);
        return changed ? extra.size() : 0;
    }

    // ------------------------------------------------------------------
    // Instance state: inputs and observable results. The target method is
    // literally c()V like the official one.
    /** k-side edge list (null -> early return, official bci 58-63 analog). */
    final List<Edge> edges;
    /** TriangleList analog: insertion-ordered set, fresh iterator per edge. */
    final LinkedHashSet<Tri> tris;
    /** Extra edges merged into matchList post-window (Phase-5 addAll analog). */
    final List<Edge> phase5Extra;
    final Observer obs;

    /** Post-call observables (result of the whole method). */
    public ArrayList<Edge> matchListOut;
    public ArrayList<Edge> poppedOut;

    public OwnWindow(List<Edge> edges, LinkedHashSet<Tri> tris,
            List<Edge> phase5Extra, Observer obs) {
        this.edges = edges;
        this.tris = tris;
        this.phase5Extra = phase5Extra;
        this.obs = obs;
    }

    /**
     * The weave target. Contains BOTH new-ArrayList sites, like official c()V:
     * the Phase-3 matchList (pinned ASTORE slot) and a Phase-4 list.
     */
    public final void c() {
        if (edges == null) return;                       // null-input early return
        ArrayList<Edge> matchList = new ArrayList<>();   // <-- pinned site (woven)
        for (Iterator<Edge> eit = edges.iterator(); eit.hasNext(); ) {
            Edge ej = eit.next();
            Intrinsics.checkNotNullExpressionValue(ej, "");
            obs.edgeNext(ej);
            for (Iterator<Tri> tit = tris.iterator(); tit.hasNext(); ) {
                Tri l = tit.next();
                Intrinsics.checkNotNullExpressionValue(l, "");
                obs.triNext(l);
                Edge eAB = new Edge(l.a(), l.b()); obs.candidate(0, eAB);
                Edge eBC = new Edge(l.b(), l.c()); obs.candidate(1, eBC);
                Edge eCA = new Edge(l.c(), l.a()); obs.candidate(2, eCA);
                boolean fAB = intersects(ej, eAB) != null; obs.flag(0, fAB);
                boolean fBC = intersects(ej, eBC) != null; obs.flag(1, fBC);
                boolean fCA = intersects(ej, eCA) != null; obs.flag(2, fCA);
                if (fAB) {
                    boolean s = shareAny(ej, eAB); obs.share(0, s);
                    if (!s) {
                        boolean q = matchList.contains(eAB); obs.query(0, q);
                        if (!q) { matchList.add(eAB); obs.append(0, eAB); }
                    }
                }
                if (fBC) {
                    boolean s = shareAny(ej, eBC); obs.share(1, s);
                    if (!s) {
                        boolean q = matchList.contains(eBC); obs.query(1, q);
                        if (!q) { matchList.add(eBC); obs.append(1, eBC); }
                    }
                }
                if (fCA) {
                    boolean s = shareAny(ej, eCA); obs.share(2, s);
                    if (!s) {
                        boolean q = matchList.contains(eCA); obs.query(2, q);
                        if (!q) { matchList.add(eCA); obs.append(2, eCA); }
                    }
                }
            }
        }
        // Phase-4 analog: a SECOND plain ArrayList (must NOT be woven) plus a
        // bounded FIFO drain of matchList through remove(0) — the h.b loop at
        // bci 535-814. The bci 806-810 re-add of a popped head through
        // matchList.add is mirrored too (bounded: re-add at most every other
        // pop, still counted by the same i<500 gate).
        ArrayList<Edge> popped = new ArrayList<>();
        int i = 0;
        while (!isEmptyC(matchList) && i < 500) {
            Edge e = popHead(matchList);
            if (e == null) break;
            obs.phase4Pop(e);
            if (i % 2 == 0) { matchList.add(e); obs.phase4Readd(e); }
            else popped.add(e);
            i++;
        }
        // Phase-5 analog: callee-style bulk addAll targets the Phase-4 list
        // (official aload_1 = slot 8 at bci 872-879), never the matchList.
        obs.phase5AddAll(augmentAll(popped, phase5Extra));
        matchListOut = matchList;
        poppedOut = popped;
    }
}
