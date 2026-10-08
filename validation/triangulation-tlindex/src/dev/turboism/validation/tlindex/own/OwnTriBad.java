package dev.turboism.validation.tlindex.own;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import kotlin.jvm.internal.Intrinsics;

/**
 * Shape-mutant fixture for TliWeave negative gates. Mirrors OwnTri.TList
 * but breaks the pinned shapes: a(l) contains TWO LinkedHashSet.add invoke
 * sites (weaver must reject site-count!=1), and a(j) lacks the leading
 * checkNotNullParameter prologue (weaver must reject the missing anchor).
 */
public final class OwnTriBad {
    private OwnTriBad() {}

    public static class TList implements Iterable<OwnTri.L> {
        private final LinkedHashSet<OwnTri.L> b = new LinkedHashSet<>();

        public final boolean a(OwnTri.L l) {
            Intrinsics.checkNotNullParameter(l, "");
            boolean first = b.add(l);     // site 1
            if (l == null) b.add(l);      // site 2 (dead code, still a site)
            return first;
        }
        public final boolean b(OwnTri.L l) {
            Intrinsics.checkNotNullParameter(l, "");
            return b.remove(l);
        }
        public final void c() { b.clear(); }

        public final List<OwnTri.L> a(OwnTri.E e) {
            // MISSING the checkNotNullParameter prologue
            ArrayList<OwnTri.L> out = new ArrayList<>();
            for (Iterator<OwnTri.L> it = iterator(); it.hasNext(); ) {
                OwnTri.L t = it.next();
                if (t.b(e)) out.add(t);
            }
            return out;
        }

        @Override public Iterator<OwnTri.L> iterator() { return b.iterator(); }
    }
}
