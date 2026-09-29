package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Own fixture mirroring the bytecode-verified slice of 5303
 * triangulation.TriangleList / k / j / TriPoint / l. Own classes only — no
 * official bytecode is loaded or executed. This is a BEHAVIOURAL slice, not a
 * host-type-equivalence claim (fixture TriL/EdgeJ/TriPoint do not reproduce
 * Kotlin data-class equals/hashCode or identity semantics of the host types).
 *
 * Bytecode-verified facts (javap on host jar + bundled kotlin-stdlib 1.7.21):
 *  - b declared LinkedHashSet.
 *  - k.a(j,true)=directed index-pair scan; k.a(j,false)=undirected index-pair scan.
 *  - k.a(j)=raw ArrayList.add (no dedupe); k.b(j)=identity remove; k.c() mutable
 *    iterator; k.a() live list.
 *  - Intrinsics.checkNotNullParameter throws NullPointerException on stdlib 1.7.21.
 *  - TriangleList.b() evaluation order: ALL THREE edge getters run first
 *    (locals j4/j5/j6), then three contains/add pairs.
 *
 * The Observer seam is fixture instrumentation: selfcheck records events through
 * it, the benchmark passes Observer.NOOP. It does not change results — every
 * observable value still comes from the real k calls below.
 */
public final class Fixture {
    private Fixture() {}

    /** Lightweight event tap inside the build window. Default is off. */
    public interface Observer {
        Observer NOOP = new Observer() {};
        /** Fired once with the freshly constructed k before the loop begins. */
        default void created(EdgeK k) {}
        /** Fired BEFORE invoking the getter, so a faulting getter still leaves a trace. */
        default void getter(TriL tri, int which) {}
        /** Fired with the membership decision (contained==true means found). */
        default void query(EdgeJ edge, boolean contained) {}
        /** Fired with the real k.a(j) append result. */
        default void append(EdgeJ edge, boolean added) {}
    }

    /** TriPoint analog: index immutable after construction. */
    public static final class TriPoint {
        public final float x, y;
        private final int index;
        public TriPoint(float x, float y, int index) {
            this.x = x; this.y = y; this.index = index;
        }
        public int getIndex() { return index; }
    }

    /** Edge analog (j): endpoints a/b; compared by index pairs only. */
    public static class EdgeJ {
        private final TriPoint a, b;
        public EdgeJ(TriPoint a, TriPoint b) { this.a = a; this.b = b; }
        public TriPoint a() { return a; }
        public TriPoint b() { return b; }
    }

    /** Triangle analog (l): three edges via d/e/f; a getter may fault. */
    public static class TriL {
        private final EdgeJ d, e, f;
        private final int faultAt;                 // 0=none, 1=d, 2=e, 3=f
        private final RuntimeException fault;
        public TriL(EdgeJ d, EdgeJ e, EdgeJ f) { this(d, e, f, 0, null); }
        public TriL(EdgeJ d, EdgeJ e, EdgeJ f, int faultAt, RuntimeException fault) {
            this.d = d; this.e = e; this.f = f; this.faultAt = faultAt; this.fault = fault;
        }
        private void maybeThrow(int which) {
            if (which == faultAt) throw Objects.requireNonNull(fault);
        }
        public EdgeJ d() { maybeThrow(1); return d; }
        public EdgeJ e() { maybeThrow(2); return e; }
        public EdgeJ f() { maybeThrow(3); return f; }
    }

    /** Faithful port of k: live ArrayList, identical method set, real Intrinsics. */
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

    /** Reference: TriangleList.b() with the real linear k.a(j,false) queries. */
    public static class RefTriangleList {
        private final LinkedHashSet<TriL> b;
        public RefTriangleList(LinkedHashSet<TriL> b) { this.b = b; }
        public EdgeK b() { return b(Observer.NOOP); }
        public EdgeK b(Observer obs) {
            EdgeK k = new EdgeK();
            obs.created(k);
            for (TriL tri : b) {
                obs.getter(tri, 0); EdgeJ j4 = tri.d();
                obs.getter(tri, 1); EdgeJ j5 = tri.e();
                obs.getter(tri, 2); EdgeJ j6 = tri.f();
                boolean p4 = k.a(j4, false); obs.query(j4, p4);
                if (!p4) obs.append(j4, k.a(j4));
                boolean p5 = k.a(j5, false); obs.query(j5, p5);
                if (!p5) obs.append(j5, k.a(j5));
                boolean p6 = k.a(j6, false); obs.query(j6, p6);
                if (!p6) obs.append(j6, k.a(j6));
            }
            return k;
        }
    }

    /** Candidate: undirected membership answered by a discarded local
     *  HashSet<Long> keyed ((long)min<<32)|(max&0xffffffffL); appends go through
     *  the real k.a(j). The index is a local — unreachable after return. */
    public static class CandTriangleList {
        private final LinkedHashSet<TriL> b;
        public CandTriangleList(LinkedHashSet<TriL> b) { this.b = b; }
        public EdgeK b() { return b(Observer.NOOP); }
        public EdgeK b(Observer obs) {
            EdgeK k = new EdgeK();
            obs.created(k);
            HashSet<Long> seen = new HashSet<>();
            for (TriL tri : b) {
                obs.getter(tri, 0); EdgeJ j4 = tri.d();
                obs.getter(tri, 1); EdgeJ j5 = tri.e();
                obs.getter(tri, 2); EdgeJ j6 = tri.f();
                boolean p4 = queryLocal(seen, j4); obs.query(j4, p4);
                if (!p4) obs.append(j4, k.a(j4));
                boolean p5 = queryLocal(seen, j5); obs.query(j5, p5);
                if (!p5) obs.append(j5, k.a(j5));
                boolean p6 = queryLocal(seen, j6); obs.query(j6, p6);
                if (!p6) obs.append(j6, k.a(j6));
            }
            return k;
        }
        /** Same entry contract as k.a(j,false): null j -> NPE before any touch. */
        static boolean queryLocal(HashSet<Long> seen, EdgeJ j) {
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
            int i0 = j.a().getIndex(), i1 = j.b().getIndex();
            int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
            boolean contained = !seen.add(((long) lo << 32) | (hi & 0xffffffffL));
            return contained;
        }
    }
}
