package dev.turboism.validation.dmatch;

import java.util.ArrayList;

import dev.turboism.validation.dmatch.Shadow.JEdge;
import dev.turboism.validation.dmatch.Shadow.JFactory;
import dev.turboism.validation.dmatch.Shadow.Observer;
import dev.turboism.validation.dmatch.Shadow.ShadowJ;
import dev.turboism.validation.dmatch.Shadow.ShadowK;
import dev.turboism.validation.dmatch.Shadow.ShadowL;
import dev.turboism.validation.dmatch.Shadow.ShadowTriPoint;
import dev.turboism.validation.dmatch.Shadow.ShadowTriangleList;

/**
 * Bounded A/B interleaved micro-benchmark. No speed threshold, no host
 * extrapolation: reports raw ns per leg for reference (linear identity
 * contains) and candidate (IdentityHashMap membership), plus separate
 * operation dimensions (candidate constructions, flag calls, share calls,
 * membership queries, appends, result size). Timed region includes the
 * matchList and any index allocation. Result parity is asserted every leg.
 */
public final class Bench {

    static long seed = 0x9E3779B97F4A7C15L;
    static float rnd() {
        seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
        return ((seed >>> 11) & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Deterministic pseudo-random input: edges across a [0,100]^2 field of
     *  small triangles; distinct indices everywhere. */
    static Object[] input(int edges, int tris) {
        ShadowK k = new ShadowK();
        int idx = 1;
        for (int i = 0; i < edges; i++) {
            float y = rnd() * 100f;
            k.a().add(new ShadowJ(new ShadowTriPoint(rnd() * 40f - 10f, y, idx++),
                                  new ShadowTriPoint(60f + rnd() * 40f, y + rnd() * 10f - 5f, idx++)));
        }
        ShadowTriangleList tl = new ShadowTriangleList();
        for (int i = 0; i < tris; i++) {
            float bx = rnd() * 100f, by = rnd() * 100f;
            ShadowTriPoint a = new ShadowTriPoint(bx, by, idx++);
            ShadowTriPoint b = new ShadowTriPoint(bx + 1f + rnd() * 4f, by + rnd() * 2f, idx++);
            ShadowTriPoint c = new ShadowTriPoint(bx + rnd() * 2f, by + 1f + rnd() * 4f, idx++);
            tl.a(new ShadowL(a, b, c));
        }
        return new Object[]{k, tl};
    }

    interface Impl {
        ArrayList<JEdge> run(ShadowK k, ShadowTriangleList tl, JFactory f, Observer o);
    }

    /** Operation counters via the observer seam (kept outside timing). */
    static final class Count implements Observer {
        int edges, tris, cands, flags, shares, queries, appends;
        public void edgeNext(JEdge e)        { edges++; }
        public void triNext(ShadowL t)       { tris++; }
        public void candidate(int i, JEdge c){ cands++; }
        public void flag(int i, boolean v)   { flags++; }
        public void share(int i, boolean v)  { shares++; }
        public void query(int i, boolean v)  { queries++; }
        public void append(int i, JEdge c)   { appends++; }
    }

    static long timeOnce(Impl impl, ShadowK k, ShadowTriangleList tl) {
        long t0 = System.nanoTime();
        ArrayList<JEdge> r = impl.run(k, tl, JFactory.DEFAULT, Observer.NOOP);
        long t1 = System.nanoTime();
        return (t1 - t0) + (r == null ? 0 : r.size()) /* keep result live */;
    }

    static String digest(Impl impl, ShadowK k, ShadowTriangleList tl) {
        Count c = new Count();
        ArrayList<JEdge> r = impl.run(k, tl, JFactory.DEFAULT, c);
        StringBuilder sb = new StringBuilder();
        for (JEdge e : r)
            sb.append(e.a().getIndex()).append('>').append(e.b().getIndex()).append(';');
        return c.edges + "/" + c.tris + "/" + c.cands + "/" + c.flags + "/"
             + c.shares + "/" + c.queries + "/" + c.appends + "|" + r.size() + "|" + sb;
    }

    public static void main(String[] args) {
        Impl ref = Shadow::matchRef;
        Impl cand = Shadow::matchCand;
        int[][] sizes = {{0, 0}, {1, 1}, {2, 2}, {16, 16}, {64, 64}, {128, 64}, {128, 128},
            {512, 400}, {1200, 800}};
        for (int[] s : sizes) {
            Object[] in = input(s[0], s[1]);
            ShadowK k = (ShadowK) in[0];
            ShadowTriangleList tl = (ShadowTriangleList) in[1];
            String dr = digest(ref, k, tl), dc = digest(cand, k, tl);
            if (!dr.equals(dc)) throw new AssertionError("parity failed at " + s[0] + "x" + s[1]);
            long tr1 = timeOnce(ref, k, tl), tc1 = timeOnce(cand, k, tl);
            long tc2 = timeOnce(cand, k, tl), tr2 = timeOnce(ref, k, tl);
            int bar2 = dr.indexOf('|', dr.indexOf('|') + 1);
            System.out.printf("size edges=%d tris=%d ops=%s%n", s[0], s[1], dr.substring(0, bar2));
            System.out.printf("  ref  ns A=%d B=%d   cand ns A=%d B=%d%n", tr1, tr2, tc1, tc2);
        }
        System.out.println("DMATCH_BENCH DONE");
    }
}
