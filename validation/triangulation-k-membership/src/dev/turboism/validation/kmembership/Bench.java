package dev.turboism.validation.kmembership;

import java.util.LinkedHashSet;

import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.TriL;
import dev.turboism.validation.kmembership.Fixture.TriPoint;

/**
 * Bounded A/B interleaved micro-benchmark: window build cost including index
 * allocation. Raw timings only — no pass threshold, no host extrapolation.
 * Sample counts are printed separately from times on purpose.
 */
public final class Bench {
    private Bench() {}

    static TriPoint pt(int i) { return new TriPoint(i, i, i); }
    static EdgeJ edge(int a, int b) { return new EdgeJ(pt(a), pt(b)); }

    static LinkedHashSet<TriL> scene(int tris) {
        LinkedHashSet<TriL> s = new LinkedHashSet<>();
        for (int i = 0; i < tris; i++) {
            int a = i, b = i + 1, c = i + 2;                 // shared edges across neighbours
            s.add(new TriL(edge(a, b), edge(b, c), edge(c, a)));
        }
        return s;
    }

    static long runRef(LinkedHashSet<TriL> tris) {
        long t0 = System.nanoTime();
        EdgeK k = new EdgeK();
        for (TriL t : tris) {
            EdgeJ a = t.d(), b = t.e(), c = t.f();
            if (!k.a(a, false)) k.a(a);
            if (!k.a(b, false)) k.a(b);
            if (!k.a(c, false)) k.a(c);
        }
        return System.nanoTime() - t0;
    }

    static long runCand(LinkedHashSet<TriL> tris) {
        long t0 = System.nanoTime();
        EdgeK k = new EdgeK();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (TriL t : tris) {
            for (EdgeJ j : new EdgeJ[] { t.d(), t.e(), t.f() }) {
                kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
                int i0 = j.a().getIndex(), i1 = j.b().getIndex();
                int lo = Math.min(i0, i1), hi = Math.max(i0, i1);
                if (seen.add(((long) lo << 32) | (hi & 0xffffffffL))) k.a(j);
            }
        }
        return System.nanoTime() - t0;
    }

    public static void main(String[] a) {
        int[] sizes = { 128, 1024, 4096 };
        int rounds = 7;
        for (int n : sizes) {
            LinkedHashSet<TriL> scene = scene(n);
            // warm both paths
            for (int i = 0; i < 3; i++) { runRef(scene); runCand(scene); }
            long refNs = 0, candNs = 0;
            for (int r = 0; r < rounds; r++) {
                refNs += runRef(scene);
                candNs += runCand(scene);
            }
            long edges = n * 3L;
            System.out.printf("KBUILD_BENCH tris=%d queries=%d refNs=%d candNs=%d rounds=%d%n",
                n, edges, refNs, candNs, rounds);
        }
        System.out.println("KBUILD_BENCH_DONE no-threshold=true extrapolation=forbidden");
    }
}
