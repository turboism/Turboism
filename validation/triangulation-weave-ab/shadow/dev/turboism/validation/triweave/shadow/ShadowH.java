package dev.turboism.validation.triweave.shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DWEAVE shadow fixture: the {@code c()} method reproduces the javap-verified
 * allocation-site shape of official {@code h.c()V} under own types —
 *
 *  - ONE {@code new ArrayList} Phase-3 match-list (the weave target; pinned by
 *    its ASTORE slot in {@code WeaveAbConfig.SHADOW_DM_WEAVE}), followed by the
 *    match window: outer edge iteration x per-edge fresh inner triangle
 *    iteration, three fresh candidate edges per triangle, each gated by
 *    {@code flag -> !shareAny -> !contains -> add}.
 *  - a SECOND {@code new ArrayList} site later in the same method (the Phase-4
 *    analog — official bci 523-530). The pinned-slot + total-sites double pin
 *    must select exactly the first site and leave this one plain.
 *  - post-window consumers on the SAME matchList instance mirroring the
 *    official callee shapes: Collection-interface isEmpty, remove(0) FIFO
 *    drain with a conditional re-add through matchList.add, and a bulk addAll
 *    that targets the SECOND list (never the matchList). No post-window
 *    contains executes — exactly the unobservability argument of the official
 *    window.
 *
 * ShadowJ is IDENTITY-ONLY like official j, so the IdentityHashMap mirror in
 * ShadowMatchList is the unconditionally-equivalent contains model. The
 * deterministic index predicates stand in for r.a/h.a — the fixture proves the
 * woven/unwoven equivalence, not the geometry.
 */
public final class ShadowH {
    final List<ShadowJ> edges;              // null -> early return (official bci 58-63)
    final LinkedHashSet<ShadowL> tris;
    final List<ShadowJ> phase5Extra;

    /** Post-call observables (selfcheck asserts type + canonical content). */
    public ArrayList<ShadowJ> matchListOut;
    public ArrayList<ShadowJ> poppedOut;

    public ShadowH(List<ShadowJ> edges, LinkedHashSet<ShadowL> tris,
            List<ShadowJ> phase5Extra) {
        this.edges = edges;
        this.tris = tris;
        this.phase5Extra = phase5Extra;
    }

    /** r.a-intersection flag analog: deterministic index predicate so both
     *  branches are exercised; pure and side-effect free like the official. */
    static boolean flag(ShadowJ ej, ShadowJ cand) {
        return ((ej.x().index() + ej.y().index()
                + cand.x().index() + cand.y().index()) & 1) == 0;
    }

    /** h.a(j,j)Z analog: undirected shared-endpoint-INDEX predicate. */
    static boolean shareAny(ShadowJ a, ShadowJ b) {
        return a.x().index() == b.x().index() || a.x().index() == b.y().index()
            || a.y().index() == b.x().index() || a.y().index() == b.y().index();
    }

    /** h.b(ArrayList) analog: pop head via remove(0), null on empty. Not
     *  mirrored by ShadowMatchList — unobservable because no contains runs
     *  after the window (same javap argument as the official class). */
    static ShadowJ popHead(ArrayList<ShadowJ> l) { return l.isEmpty() ? null : l.remove(0); }

    /** Collection-interface isEmpty call site (bci 535-540 analog). */
    static boolean isEmptyC(Collection<?> c) { return c.isEmpty(); }

    /** h.a(ArrayList,j,TL,k) analog: bulk addAll on the PHASE-4 list. */
    static int augmentAll(ArrayList<ShadowJ> l, Collection<ShadowJ> extra) {
        return l.addAll(extra) ? extra.size() : 0;
    }

    /** The weave target — name and descriptor c()V like official h.c()V.
     *  Contains BOTH new-ArrayList sites on distinct ASTORE slots. */
    public final void c() {
        if (edges == null) return;                       // null-input early return
        ArrayList<ShadowJ> matchList = new ArrayList<>();  // <-- pinned site (woven)
        for (ShadowJ ej : edges) {
            for (ShadowL t : tris) {
                ShadowJ c1 = new ShadowJ(t.d().x(), t.d().y());
                ShadowJ c2 = new ShadowJ(t.e().x(), t.e().y());
                ShadowJ c3 = new ShadowJ(t.f().x(), t.f().y());
                if (flag(ej, c1) && !shareAny(ej, c1)
                        && !matchList.contains(c1)) matchList.add(c1);
                if (flag(ej, c2) && !shareAny(ej, c2)
                        && !matchList.contains(c2)) matchList.add(c2);
                if (flag(ej, c3) && !shareAny(ej, c3)
                        && !matchList.contains(c3)) matchList.add(c3);
            }
        }
        // Phase-4 analog: a SECOND plain ArrayList (must NOT be woven) plus a
        // bounded FIFO drain of matchList through remove(0) with a conditional
        // re-add — the bci 535-814 loop shape.
        ArrayList<ShadowJ> popped = new ArrayList<>();
        int i = 0;
        while (!isEmptyC(matchList) && i < 500) {
            ShadowJ e = popHead(matchList);
            if (e == null) break;
            if (i % 2 == 0) matchList.add(e); else popped.add(e);
            i++;
        }
        // Phase-5 analog: bulk addAll targets the second list, never matchList.
        augmentAll(popped, phase5Extra);
        matchListOut = matchList;
        poppedOut = popped;
    }
}
