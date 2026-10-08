package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.List;

import dev.turboism.validation.tlindex.Shadow.RefTriangleList;
import dev.turboism.validation.tlindex.Shadow.ShadowJ;
import dev.turboism.validation.tlindex.Shadow.ShadowL;
import dev.turboism.validation.tlindex.Shadow.ShadowTriPoint;

/**
 * Bounded A/B interleaved micro-benchmark on host-scale meshes. No speed
 * threshold, no host extrapolation: reports raw ns per impl plus operation
 * dimensions (queries, flips). Timed loop = query+flip mix mirroring the
 * Lawson interleave; result parity asserted each leg.
 */
public final class Bench {
    static long seed = 0x9E3779B97F4A7C15L;
    static int rnd(int n) {
        seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
        return (int) (((seed >>> 16) & 0x7fffffffL) % n);
    }

    interface Lst {
        boolean add(ShadowL t);
        boolean rem(ShadowL t);
        List<ShadowL> q(ShadowJ e);
        int size();
    }

    static final class Ref implements Lst {
        final RefTriangleList l = new RefTriangleList();
        public boolean add(ShadowL t) { return l.a(t); }
        public boolean rem(ShadowL t) { return l.b(t); }
        public List<ShadowL> q(ShadowJ e) { return l.a(e); }
        public int size() { return l.size(); }
    }
    static final class Idx implements Lst {
        final IdxList l = new IdxList();
        public boolean add(ShadowL t) { return l.a(t); }
        public boolean rem(ShadowL t) { return l.b(t); }
        public List<ShadowL> q(ShadowJ e) { return l.a(e); }
        public int size() { return l.size(); }
    }

    static int queries, flips, hitTris;

    /** Grid mesh of side x side cells (~2*(side-1)^2 triangles), then a
     *  Lawson-flip loop: per diagonal edge, a(j) neighbour query; every
     *  flipStride-th shared edge is flipped (remove both + add flipped
     *  pair). Plus extra neighbour probes, mirroring Lawson's query-heavy
     *  interleave. flipStride=1 is adversarial (flip nearly every edge);
     *  larger values approach the observed host ratio (query:remove ~12:1
     *  in JFR samples). */
    static long leg(boolean idx, int side, int passes, int flipStride) {
        Lst l = idx ? new Idx() : new Ref();
        int n = side * side;
        ShadowTriPoint[] v = new ShadowTriPoint[n];
        for (int i = 0; i < n; i++) v[i] = new ShadowTriPoint(i % side, i / side, i);
        for (int y = 0; y < side - 1; y++)
            for (int x = 0; x < side - 1; x++) {
                int a = y * side + x, b = a + 1, c = a + side, d = a + side + 1;
                l.add(new ShadowL(v[a], v[b], v[d]));
                l.add(new ShadowL(v[a], v[d], v[c]));
            }
        queries = 0; flips = 0; hitTris = 0;
        long t0 = System.nanoTime();
        int shared = 0;
        for (int p = 0; p < passes; p++) {
            for (int i = 0; i < n - side - 1; i++) {
                int a = i, b = i + side + 1;              // a diagonal edge
                List<ShadowL> hit = l.q(new ShadowJ(v[a], v[b]));
                queries++; hitTris += hit.size();
                if (hit.size() == 2 && ++shared % flipStride == 0) {
                    int o1 = a + 1, o2 = a + side;
                    l.rem(hit.get(0)); l.rem(hit.get(1));
                    l.add(new ShadowL(v[a], v[o1], v[o2]));
                    l.add(new ShadowL(v[a], v[o2], v[b]));
                    flips++;
                }
                l.q(new ShadowJ(v[a], v[a + 1])); queries++;
                l.q(new ShadowJ(v[a], v[a + side])); queries++;
            }
        }
        return System.nanoTime() - t0;
    }

    public static void main(String[] args) {
        int side = 30;                 // ~1682 triangles, host-scale per mesh
        for (int stride : new int[] {1, 12, 1000}) {   // adversarial, ~host ratio, near-pure queries
            for (int rep = 0; rep < 3; rep++) {
                long r = leg(false, side, 6, stride);
                int rq = queries, rf = flips, rh = hitTris;
                long i = leg(true, side, 6, stride);
                if (queries != rq || flips != rf || hitTris != rh)
                    throw new AssertionError("bench legs diverged in op dimensions");
                System.out.printf("stride=%-4d ref=%dms  idx=%dms  queries=%d  flips=%d  hitTris=%d%n",
                    stride, r / 1_000_000, i / 1_000_000, rq, rf, rh);
            }
        }
        System.out.println("TLINDEX_BENCH DONE");
    }
}
