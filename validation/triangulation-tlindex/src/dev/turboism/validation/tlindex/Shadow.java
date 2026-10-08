package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Own shadow fixture mirroring the bytecode-verified slice of 5303
 * triangulation {TriPoint, j, l, TriangleList}. Own classes only - no
 * official bytecode is loaded or executed. This is a BEHAVIOURAL slice,
 * not a host-type-equivalence claim.
 *
 * Bytecode-verified facts (javap on reviewed Live2D_Cubism.jar 5.3.03,
 * jar sha256 bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166):
 *  - TriangleList.b is a private final LinkedHashSet<l>. Mutation entry
 *    points are exactly a(l)Z = b.add(l), b(l)Z = b.remove(l), c()V =
 *    b.clear(). c(l)Z = b.contains(l) is read-only. iterator() exposes the
 *    set iterator (remove() through it is a side path - covered by the
 *    size/epoch self-check, and no such caller was found in h).
 *  - TriangleList.a(j)List: allocates ArrayList, iterates the whole set,
 *    collects every l for which l.b(j) is true, returns the list in
 *    LinkedHashSet insertion order. checkNotNullParameter(j,"") prologue.
 *  - l.b(j)Z: undirected ENDPOINT-INDEX match - true iff any of the
 *    triangle's three edges (a,b),(b,c),(c,a) equals edge j's endpoint pair
 *    in either direction, comparing TriPoint.getIndex() ints. Sentinels
 *    (-1,-2,-3) are legal indices.
 *  - l.equals(Object): cyclic vertex-permutation compare of the three
 *    TriPoint fields via Intrinsics.areEqual -> TriPoint.equals, which per
 *    the T029-DMATCH shadow notes compares x,y ONLY (index ignored).
 *    Therefore two l objects can be equal while carrying DIFFERENT vertex
 *    indices (equal-coords-different-index TriPoints).
 *  - l.hashCode() = constant 0 -> the LinkedHashSet degenerates to one
 *    bucket chain: every add/remove/contains is an O(T) equals scan. No
 *    TriangleList method mutates an l's TriPoint fields; they are
 *    constructor-assigned only (verified: putfields only in <init>).
 *  - a(j) returns a FRESH ArrayList snapshot; callers may keep it.
 */
public final class Shadow {
    private Shadow() {}

    /** TriPoint slice: coordinates + index; equals compares x,y only
     *  (verified), hashCode the verified formula. */
    public static class ShadowTriPoint {
        public final float x, y;
        public final int index;
        public ShadowTriPoint(float x, float y, int index) { this.x = x; this.y = y; this.index = index; }
        public int getIndex() { return index; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ShadowTriPoint)) return false;
            ShadowTriPoint p = (ShadowTriPoint) o;
            return Float.compare(x, p.x) == 0 && Float.compare(y, p.y) == 0;
        }
        @Override public int hashCode() {
            return 31 * (31 * index + Float.hashCode(x)) + Float.hashCode(y);
        }
    }

    /** j slice: two TriPoint endpoints; identity semantics (no equals). */
    public static class ShadowJ {
        public final ShadowTriPoint a, b;
        public ShadowJ(ShadowTriPoint a, ShadowTriPoint b) { this.a = a; this.b = b; }
        public ShadowTriPoint a() { return a; }
        public ShadowTriPoint b() { return b; }
    }

    /** l slice: three TriPoint vertices, verified equals/hashCode/b(j). */
    public static class ShadowL {
        public final ShadowTriPoint a, b, c;
        public ShadowL(ShadowTriPoint a, ShadowTriPoint b, ShadowTriPoint c) {
            this.a = a; this.b = b; this.c = c;
        }
        public ShadowTriPoint a() { return a; }
        public ShadowTriPoint b() { return b; }
        public ShadowTriPoint c() { return c; }

        /** Verified semantics: true iff any of edges (a,b),(b,c),(c,a)
         *  matches j's endpoint pair undirectedly, by getIndex(). */
        public boolean b(ShadowJ e) {
            int ja = e.a().getIndex(), jb = e.b().getIndex();
            int ia = a.getIndex(), ib = b.getIndex(), ic = c.getIndex();
            return (ia == ja && ib == jb) || (ia == jb && ib == ja)
                || (ib == ja && ic == jb) || (ib == jb && ic == ja)
                || (ic == ja && ia == jb) || (ic == jb && ia == ja);
        }

        /** Verified: cyclic vertex permutations of TriPoint-equality
         *  (TriPoint.equals compares x,y only - index ignored). */
        @Override public boolean equals(Object o) {
            if (!(o instanceof ShadowL)) return false;
            ShadowL t = (ShadowL) o;
            ShadowTriPoint[] x = {a, b, c};
            ShadowTriPoint[] y = {t.a, t.b, t.c};
            int[][] perms = {{0,1,2},{1,2,0},{2,0,1},{0,2,1},{2,1,0},{1,0,2}};
            for (int[] p : perms) {
                if (x[0].equals(y[p[0]]) && x[1].equals(y[p[1]]) && x[2].equals(y[p[2]]))
                    return true;
            }
            return false;
        }
        @Override public int hashCode() { return 0; }   // verified constant
    }

    /** Reference ShadowTriangleList: verbatim official semantics
     *  (brute-force a(j) scan, LinkedHashSet store). */
    public static class RefTriangleList {
        public final LinkedHashSet<ShadowL> b = new LinkedHashSet<>();
        public boolean a(ShadowL tri) { return b.add(tri); }
        public boolean b(ShadowL tri) { return b.remove(tri); }
        public void c() { b.clear(); }
        public boolean c(ShadowL tri) { return b.contains(tri); }
        public List<ShadowL> a(ShadowJ e) {
            ArrayList<ShadowL> out = new ArrayList<>();
            for (Iterator<ShadowL> it = b.iterator(); it.hasNext(); ) {
                ShadowL t = it.next();
                if (t.b(e)) out.add(t);
            }
            return out;
        }
        public Iterator<ShadowL> iterator() { return b.iterator(); }
        public int size() { return b.size(); }
    }
}
