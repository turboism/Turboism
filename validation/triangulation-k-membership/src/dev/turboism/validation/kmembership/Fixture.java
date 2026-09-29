package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Own fixture mirroring the reviewed 5303 shape of
 * triangulation.TriangleList / k / j / TriPoint / l.
 * Own classes only — no official bytecode is loaded or executed.
 *
 * Verified from bundled javap (reported facts, re-verified against host stdlib 1.7.21):
 *  - b declared LinkedHashSet; iterator() single ARETURN after checkNotNullExpressionValue.
 *  - k.a(j,true)  = directed index-pair scan; k.a(j,false) = undirected index-pair scan.
 *  - k.a(j) = raw ArrayList.add (no dedupe); k.b(j) = identity remove; k.c() mutable
 *    iterator; k.a() live list.
 *  - Intrinsics.checkNotNullParameter throws NullPointerException (NOT IAE) on
 *    kotlin-stdlib 1.7.21 — verified against the host's own jar.
 *  - TriangleList.b() evaluation order: all three edge getters l.d()/e()/f() run FIRST
 *    (store locals), then three contains/add pairs — preserved here.
 */
public final class Fixture {
    private Fixture() {}

    /** TriPoint analog: index immutable after construction. */
    public static final class TriPoint {
        public final float x, y;
        private final int index;
        public TriPoint(float x, float y, int index) {
            this.x = x; this.y = y; this.index = index;
        }
        public int getIndex() { return index; }
    }

    /** Edge analog (j): endpoints a/b, value-compared by index pairs only. */
    public static class EdgeJ {
        private final TriPoint a, b;
        public EdgeJ(TriPoint a, TriPoint b) { this.a = a; this.b = b; }
        public TriPoint a() { return a; }
        public TriPoint b() { return b; }
    }

    /** Triangle analog (l): three edges via d/e/f getters; getters may fault. */
    public static class TriL {
        private final EdgeJ d, e, f;
        private final RuntimeException fault;
        public TriL(EdgeJ d, EdgeJ e, EdgeJ f) { this(d, e, f, null); }
        public TriL(EdgeJ d, EdgeJ e, EdgeJ f, RuntimeException fault) {
            this.d = d; this.e = e; this.f = f; this.fault = fault;
        }
        public EdgeJ d() { if (fault != null) throw fault; return d; }
        public EdgeJ e() { if (fault != null) throw fault; return e; }
        public EdgeJ f() { if (fault != null) throw fault; return f; }
    }

    /** Faithful port of k: live ArrayList, identical method set. */
    public static class EdgeK {
        protected final ArrayList<EdgeJ> a = new ArrayList<>();
        public ArrayList<EdgeJ> a() { return a; }
        public int b() { return a.size(); }
        public Iterator<EdgeJ> c() { return a.iterator(); }
        public boolean a(EdgeJ j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            return a.add(j);
        }
        public boolean b(EdgeJ j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            return a.remove(j);
        }
        public boolean a(EdgeJ j, boolean directed) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            for (EdgeJ e : a) {
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

    /** Reference TriangleList.b() — linear contains inside the build window. */
    public static class RefTriangleList {
        private final LinkedHashSet<TriL> b;
        public RefTriangleList(LinkedHashSet<TriL> b) { this.b = b; }
        public EdgeK b() {
            EdgeK k = new EdgeK();
            for (TriL tri : b) {
                // Real evaluation order: all three getters BEFORE any query/add.
                EdgeJ j4 = tri.d();
                EdgeJ j5 = tri.e();
                EdgeJ j6 = tri.f();
                if (!k.a(j4, false)) k.a(j4);
                if (!k.a(j5, false)) k.a(j5);
                if (!k.a(j6, false)) k.a(j6);
            }
            return k;
        }
    }

    /** Candidate: same k instance built through real adds; window query served by a
     *  discarded local HashSet<Long> keyed on the undirected (min,max) index pair.
     *  Index never stored on the returned k; post-return mutations cannot see it. */
    public static class CandTriangleList {
        private final LinkedHashSet<TriL> b;
        public CandTriangleList(LinkedHashSet<TriL> b) { this.b = b; }
        public EdgeK b() {
            EdgeK k = new EdgeK();
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (TriL tri : b) {
                EdgeJ j4 = tri.d();
                EdgeJ j5 = tri.e();
                EdgeJ j6 = tri.f();
                if (addIfAbsent(k, seen, j4)) k.a(j4);
                if (addIfAbsent(k, seen, j5)) k.a(j5);
                if (addIfAbsent(k, seen, j6)) k.a(j6);
            }
            return k;
        }
        /** Same entry contract as k.a(j,false): null -> NPE first, then undirected check. */
        private static boolean addIfAbsent(EdgeK k, java.util.HashSet<Long> seen, EdgeJ j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            int i0 = j.a().getIndex(), i1 = j.b().getIndex();
            int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
            // ((long)min << 32) | (max & 0xffffffffL) — low 32 masked per frozen spec.
            return seen.add(((long) lo << 32) | (hi & 0xffffffffL));
        }
    }
}
