package dev.turboism.validation.dmatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import dev.turboism.validation.dmatch.Shadow.JEdge;
import dev.turboism.validation.dmatch.Shadow.JFactory;
import dev.turboism.validation.dmatch.Shadow.Observer;
import dev.turboism.validation.dmatch.Shadow.ShadowJ;
import dev.turboism.validation.dmatch.Shadow.ShadowK;
import dev.turboism.validation.dmatch.Shadow.ShadowL;
import dev.turboism.validation.dmatch.Shadow.ShadowR;
import dev.turboism.validation.dmatch.Shadow.ShadowTriangleList;
import dev.turboism.validation.dmatch.Shadow.ShadowTriPoint;

/**
 * Deliberately-broken candidate variants. Each is a full copy of the window
 * with exactly one injected defect, marked BROKEN. Every one must be rejected
 * by the SelfCheck comparator on its discriminating domain.
 */
public final class Negatives {
    private Negatives() {}

    private static boolean share(JEdge x, JEdge y) { return Shadow.shareAnyEndpoint(x, y); }

    /** BROKEN: value-keyed dedup - treats equal-endpoint distinct objects as
     *  duplicates. The real contains is identity; this drops appends. */
    public static ArrayList<JEdge> matchValueDedup(ShadowK k, ShadowTriangleList tl,
                                                 JFactory factory, Observer obs) {
        if (k == null) return null;
        ArrayList<JEdge> match = new ArrayList<>();
        Set<Long> seen = new HashSet<>();                 // BROKEN: value key
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s) {
                            int i0 = c[i].a().getIndex(), i1 = c[i].b().getIndex();
                            long key = ((long) Math.min(i0, i1) << 32) | (Math.max(i0, i1) & 0xffffffffL);
                            boolean q = !seen.add(key);   // BROKEN
                            obs.query(i, q);
                            if (!q) { match.add(c[i]); obs.append(i, c[i]); }
                        }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: share-check removed - endpoint-sharing candidates append. */
    public static ArrayList<JEdge> matchNoShare(ShadowK k, ShadowTriangleList tl,
                                                JFactory factory, Observer obs) {
        if (k == null) return null;
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        // BROKEN: no share call
                        boolean q = match.contains(c[i]); obs.query(i, q);
                        if (!q) { match.add(c[i]); obs.append(i, c[i]); }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: directed-only share-check (a==a && b==b) misses cross-endpoint
     *  sharing (a==b' / b==a'). */
    public static ArrayList<JEdge> matchDirectedShare(ShadowK k, ShadowTriangleList tl,
                                                      JFactory factory, Observer obs) {
        if (k == null) return null;
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean s = ej.a().getIndex() == c[i].a().getIndex()
                                 && ej.b().getIndex() == c[i].b().getIndex(); // BROKEN: directed
                        obs.share(i, s);
                        if (!s) {
                            boolean q = match.contains(c[i]); obs.query(i, q);
                            if (!q) { match.add(c[i]); obs.append(i, c[i]); }
                        }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: membership query evaluated BEFORE the share-check. */
    public static ArrayList<JEdge> matchSwapOrder(ShadowK k, ShadowTriangleList tl,
                                                  JFactory factory, Observer obs) {
        if (k == null) return null;
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean q = match.contains(c[i]); obs.query(i, q);   // BROKEN: first
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s && !q) { match.add(c[i]); obs.append(i, c[i]); }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: appends a NEW equal-structure object instead of the constructed
     *  candidate - same values, wrong identity. */
    public static ArrayList<JEdge> matchAppendNew(ShadowK k, ShadowTriangleList tl,
                                                  JFactory factory, Observer obs) {
        if (k == null) return null;
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s) {
                            boolean q = match.contains(c[i]); obs.query(i, q);
                            if (!q) {
                                JEdge copy = factory.make(c[i].a(), c[i].b());  // BROKEN: wrong ref
                                match.add(copy);
                                obs.append(i, copy);
                            }
                        }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: candidates built with swapped endpoints j(b,a), j(c,b), j(a,c). */
    public static ArrayList<JEdge> matchReversed(ShadowK k, ShadowTriangleList tl,
                                                 JFactory factory, Observer obs) {
        if (k == null) return null;
        ArrayList<JEdge> match = new ArrayList<>();
        for (Iterator<JEdge> eit = k.c(); eit.hasNext(); ) {
            JEdge ej = eit.next();
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(ej, "");
            obs.edgeNext(ej);
            for (Iterator<ShadowL> tit = tl.iterator(); tit.hasNext(); ) {
                ShadowL l = tit.next();
                kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(l, "");
                obs.triNext(l);
                JEdge eAB = factory.make(l.b(), l.a()); obs.candidate(0, eAB); // BROKEN: reversed
                JEdge eBC = factory.make(l.c(), l.b()); obs.candidate(1, eBC);
                JEdge eCA = factory.make(l.a(), l.c()); obs.candidate(2, eCA);
                boolean fAB = ShadowR.a.a(ej, eAB) != null; obs.flag(0, fAB);
                boolean fBC = ShadowR.a.a(ej, eBC) != null; obs.flag(1, fBC);
                boolean fCA = ShadowR.a.a(ej, eCA) != null; obs.flag(2, fCA);
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s) {
                            boolean q = match.contains(c[i]); obs.query(i, q);
                            if (!q) { match.add(c[i]); obs.append(i, c[i]); }
                        }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN: gate evaluation order CA, AB, BC (append/event order changes). */
    public static ArrayList<JEdge> matchGateOrder(ShadowK k, ShadowTriangleList tl,
                                                  JFactory factory, Observer obs) {
        if (k == null) return null;
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                int[] order = {2, 0, 1};                                    // BROKEN
                for (int i : order) {
                    if (f[i]) {
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s) {
                            boolean q = match.contains(c[i]); obs.query(i, q);
                            if (!q) { match.add(c[i]); obs.append(i, c[i]); }
                        }
                    }
                }
            }
        }
        return match;
    }

    /** BROKEN (on non-identity j): HashSet mirror relying on j's own
     *  equals/hashCode - collapses whenever equals/hashCode are inconsistent
     *  (brokenHash variant) or simply not the verified semantics. */
    public static ArrayList<JEdge> matchHashSetMirror(ShadowK k, ShadowTriangleList tl,
                                                      JFactory factory, Observer obs) {
        if (k == null) return null;
        ArrayList<JEdge> match = new ArrayList<>();
        Set<JEdge> seen = new HashSet<>();                  // BROKEN: value-hash dependence
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
                JEdge[] c = {eAB, eBC, eCA};
                boolean[] f = {fAB, fBC, fCA};
                for (int i = 0; i < 3; i++) {
                    if (f[i]) {
                        boolean s = share(ej, c[i]); obs.share(i, s);
                        if (!s) {
                            boolean q = seen.contains(c[i]); obs.query(i, q);
                            if (!q) { match.add(c[i]); seen.add(c[i]); obs.append(i, c[i]); }
                        }
                    }
                }
            }
        }
        return match;
    }
}
