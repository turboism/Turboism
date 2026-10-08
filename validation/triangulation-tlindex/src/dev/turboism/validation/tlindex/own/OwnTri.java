package dev.turboism.validation.tlindex.own;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import kotlin.jvm.internal.Intrinsics;

/**
 * Own fixture mirroring the javap-verified bytecode shape of 5.3.03
 * TriangleList at the four weave points: a(l)Z and b(l)Z carry the
 * checkNotNullParameter prologue plus a flag-gated log block, then exactly
 * one LinkedHashSet.add / .remove invoke; c()V exactly one .clear invoke;
 * a(j)List is the prologue + ArrayList + iterator scan returning the
 * insertion-ordered hit list.
 *
 * Own types stand in for official l/j/TriPoint: OwnL exposes a()/b()/c()
 * TriPoint getters and the undirected-index-pair b(j) predicate;
 * OwnTriPoint.getIndex() returns the vertex index (sentinel negatives are
 * legal). equals/hashCode reproduce the official contract (cyclic
 * TriPoint-permutation equals ignoring index; constant-0 hashCode).
 */
public final class OwnTri {
    private OwnTri() {}

    public static class Pt {
        public final float x, y;
        public final int index;
        public Pt(float x, float y, int index) { this.x = x; this.y = y; this.index = index; }
        public int getIndex() { return index; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Pt)) return false;
            Pt p = (Pt) o;
            return Float.compare(x, p.x) == 0 && Float.compare(y, p.y) == 0;
        }
        @Override public int hashCode() {
            return 31 * (31 * index + Float.hashCode(x)) + Float.hashCode(y);
        }
    }

    public static class E {
        public final Pt a, b;
        public E(Pt a, Pt b) { this.a = a; this.b = b; }
        public Pt a() { return a; }
        public Pt b() { return b; }
    }

    public static class L {
        public final Pt a, b, c;
        public L(Pt a, Pt b, Pt c) { this.a = a; this.b = b; this.c = c; }
        public Pt a() { return a; }
        public Pt b() { return b; }
        public Pt c() { return c; }
        public boolean b(E e) {
            int ja = e.a().getIndex(), jb = e.b().getIndex();
            int ia = a.getIndex(), ib = b.getIndex(), ic = c.getIndex();
            return (ia == ja && ib == jb) || (ia == jb && ib == ja)
                || (ib == ja && ic == jb) || (ib == jb && ic == ja)
                || (ic == ja && ia == jb) || (ic == jb && ia == ja);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof L)) return false;
            L t = (L) o;
            Pt[] x = {a, b, c}, y = {t.a, t.b, t.c};
            int[][] perms = {{0,1,2},{1,2,0},{2,0,1},{0,2,1},{2,1,0},{1,0,2}};
            for (int[] p : perms)
                if (x[0].equals(y[p[0]]) && x[1].equals(y[p[1]]) && x[2].equals(y[p[2]]))
                    return true;
            return false;
        }
        @Override public int hashCode() { return 0; }
    }

    /** The weave target shape. {@code b} is the private final LinkedHashSet,
     *  same field name as official. */
    public static class TList implements Iterable<L> {
        private final LinkedHashSet<L> b = new LinkedHashSet<>();
        static boolean debug = false;          // stands in for c$a.b() gate

        public final boolean a(L l) {
            Intrinsics.checkNotNullParameter(l, "");
            if (debug) { System.err.println("tri-add " + l); }
            return b.add(l);
        }
        public final boolean b(L l) {
            Intrinsics.checkNotNullParameter(l, "");
            if (debug) { System.err.println("tri-rm " + l); }
            return b.remove(l);
        }
        public final void c() { b.clear(); }
        public final boolean c(L l) { return b.contains(l); }

        public final List<L> a(E e) {
            Intrinsics.checkNotNullParameter(e, "");
            ArrayList<L> out = new ArrayList<>();
            for (Iterator<L> it = iterator(); it.hasNext(); ) {
                L t = it.next();
                Intrinsics.checkNotNullExpressionValue(t, "");
                if (t.b(e)) out.add(t);
            }
            return out;
        }

        @Override public Iterator<L> iterator() {
            Iterator<L> it = b.iterator();
            Intrinsics.checkNotNullExpressionValue(it, "");
            return it;
        }
        public int a() { return b.size(); }
    }
}
