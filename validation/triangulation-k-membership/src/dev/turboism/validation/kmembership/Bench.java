package dev.turboism.validation.kmembership;

import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.kmembership.Fixture.CandTriangleList;
import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.RefTriangleList;
import dev.turboism.validation.kmembership.Fixture.TriL;
import dev.turboism.validation.kmembership.Fixture.TriPoint;

/**
 * Bounded micro-benchmark driving THE SAME delivered implementations as the
 * selfcheck (RefTriangleList.b()/CandTriangleList.b() with Observer.NOOP, so
 * recording is off). Includes k construction + index allocation in the timed
 * region. A/B and B/A interleaved; every raw per-round value is printed.
 * Returned lists are consumed into a checksum AFTER the timing and verified for
 * equality each round — no pass threshold, no host extrapolation.
 */
public final class Bench {
    private Bench() {}

    static TriPoint pt(int i) { return new TriPoint(i, i, i); }
    static EdgeJ edge(int a, int b) { return new EdgeJ(pt(a), pt(b)); }

    static LinkedHashSet<TriL> scene(int tris) {
        LinkedHashSet<TriL> s = new LinkedHashSet<>();
        for (int i = 0; i < tris; i++) {
            int a = i, b = i + 1, c = i + 2;                 // shared edges between neighbours
            s.add(new TriL(edge(a, b), edge(b, c), edge(c, a)));
        }
        return s;
    }

    /** Consume the returned list outside the timed region. */
    static long checksum(EdgeK k) {
        long h = 0;
        List<EdgeJ> l = k.a();
        for (EdgeJ e : l) {
            h = h * 31 + e.a().getIndex();
            h = h * 31 + e.b().getIndex();
            h = h * 31 + System.identityHashCode(e);   // original references consumed
        }
        return h;
    }

    static boolean listEquals(EdgeK x, EdgeK y) {
        List<EdgeJ> a = x.a(), b = y.a();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i) != b.get(i)) return false;
        return true;
    }

    public static void main(String[] args) {
        int[] sizes = { 0, 1, 2, 128, 1024, 4096 };
        int rounds = 7;
        boolean allEqual = true;
        for (int n : sizes) {
            LinkedHashSet<TriL> scene = scene(n);
            long checkRef = 0, checkCand = 0;
            for (int r = 0; r < rounds; r++) {
                boolean refFirst = (r % 2 == 0);       // A/B then B/A alternation
                long refNs, candNs;
                EdgeK rk, ck;
                if (refFirst) {
                    long t0 = System.nanoTime(); rk = new RefTriangleList(scene).b(); refNs = System.nanoTime() - t0;
                    t0 = System.nanoTime(); ck = new CandTriangleList(scene).b(); candNs = System.nanoTime() - t0;
                } else {
                    long t0 = System.nanoTime(); ck = new CandTriangleList(scene).b(); candNs = System.nanoTime() - t0;
                    t0 = System.nanoTime(); rk = new RefTriangleList(scene).b(); refNs = System.nanoTime() - t0;
                }
                checkRef ^= checksum(rk);
                checkCand ^= checksum(ck);
                if (!listEquals(rk, ck)) allEqual = false;
                System.out.printf("KBUILD_BENCH tris=%d round=%d order=%s refNs=%d candNs=%d%n",
                    n, r, refFirst ? "A-B" : "B-A", refNs, candNs);
            }
            System.out.printf("KBUILD_BENCH_SUM tris=%d refChecksum=%d candChecksum=%d listEqual=%s%n",
                n, checkRef, checkCand, listEquals(new RefTriangleList(scene).b(), new CandTriangleList(scene).b()));
        }
        if (!allEqual) { System.out.println("KBUILD_BENCH MISMATCH"); System.exit(1); }
        System.out.println("KBUILD_BENCH_DONE no-threshold=true extrapolation=forbidden");
    }
}
