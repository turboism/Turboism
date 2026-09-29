package dev.turboism.validation.triweave.shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bad-site mutant of {@link ShadowH}: the Phase-4 allocation is a Vector, so the
 * target method contains only ONE full NEW/DUP/INVOKESPECIAL/ASTORE pattern
 * site — the DWEAVE double-pin must reject with {@code total-sites=1} and the
 * woven leg must go INVALID instead of silently weaving a partial shape.
 */
public final class ShadowH {
    final List<ShadowJ> edges;
    final LinkedHashSet<ShadowL> tris;
    final List<ShadowJ> phase5Extra;

    public ArrayList<ShadowJ> matchListOut;
    public List<ShadowJ> poppedOut;

    public ShadowH(List<ShadowJ> edges, LinkedHashSet<ShadowL> tris,
            List<ShadowJ> phase5Extra) {
        this.edges = edges;
        this.tris = tris;
        this.phase5Extra = phase5Extra;
    }

    static boolean flag(ShadowJ ej, ShadowJ cand) {
        return ((ej.x().index() + ej.y().index()
                + cand.x().index() + cand.y().index()) & 1) == 0;
    }

    static boolean shareAny(ShadowJ a, ShadowJ b) {
        return a.x().index() == b.x().index() || a.x().index() == b.y().index()
            || a.y().index() == b.x().index() || a.y().index() == b.y().index();
    }

    static ShadowJ popHead(ArrayList<ShadowJ> l) { return l.isEmpty() ? null : l.remove(0); }
    static boolean isEmptyC(Collection<?> c) { return c.isEmpty(); }
    static int augmentAll(List<ShadowJ> l, Collection<ShadowJ> extra) {
        return l.addAll(extra) ? extra.size() : 0;
    }

    public final void c() {
        if (edges == null) return;
        ArrayList<ShadowJ> matchList = new ArrayList<>();   // pinned site — matches
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
        List<ShadowJ> popped = new java.util.Vector<>();    // mutant: not a pattern site
        int i = 0;
        while (!isEmptyC(matchList) && i < 500) {
            ShadowJ e = popHead(matchList);
            if (e == null) break;
            if (i % 2 == 0) matchList.add(e); else popped.add(e);
            i++;
        }
        augmentAll(popped, phase5Extra);
        matchListOut = matchList;
        poppedOut = popped;
    }
}
