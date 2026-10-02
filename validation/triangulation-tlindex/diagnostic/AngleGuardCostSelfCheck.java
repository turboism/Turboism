import java.util.Arrays;
import java.util.Random;

/** Scalar predicate/fallback costs only, excluding lease, vector construction and Editor. */
public final class AngleGuardCostSelfCheck {
    private static volatile int consumed;
    private AngleGuardCostSelfCheck() { }
    private static boolean original(float ax, float ay, float bx, float by) {
        float cross = ax * by - ay * bx;
        float dot = ax * bx + ay * by;
        float angle = (float) Math.atan2((double) cross, (double) dot);
        if (angle < 0.0f) angle += 3.1415927f * 2.0f;
        return !(angle <= 1.0e-6f);
    }
    private static boolean guarded(float ax, float ay, float bx, float by) {
        float cross = ax * by - ay * bx;
        float dot = ax * bx + ay * by;
        return AngleGuardMath.reject(cross, dot) || original(ax, ay, bx, by);
    }
    private static long run(float[] data, boolean fast, boolean adaptive, int rounds) {
        int hits = 0; long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            boolean warmed = false; int misses = 0;
            for (int i = 0; i < data.length; i += 4) {
                boolean rejected;
                if (fast && adaptive && warmed && misses < 8) {
                    float cross = data[i] * data[i + 3] - data[i + 1] * data[i + 2];
                    float dot = data[i] * data[i + 2] + data[i + 1] * data[i + 3];
                    if (AngleGuardMath.reject(cross, dot)) { rejected = true; misses = 0; }
                    else { misses++; rejected = original(data[i], data[i + 1], data[i + 2], data[i + 3]); }
                } else if (fast && !adaptive) rejected = guarded(data[i], data[i + 1], data[i + 2], data[i + 3]);
                else rejected = original(data[i], data[i + 1], data[i + 2], data[i + 3]);
                warmed = true;
                if (rejected) hits++;
            }
        }
        consumed = hits;
        return System.nanoTime() - start;
    }
    public static void main(String[] args) {
        boolean adaptive = args.length == 1 && args[0].equals("--adaptive");
        System.out.println("MODE adaptive=" + adaptive);
        Random random = new Random(0x560c05L);
        for (String distribution : new String[] {"uniformFinite", "halfNearRay", "allFallbackNearRay", "allFallbackZeroCross"}) {
            float[] data = new float[4096 * 4]; int bypasses = 0, expectedHits = 0;
            for (int i = 0; i < data.length; i += 4) {
                if (distribution.equals("uniformFinite") || (distribution.equals("halfNearRay") && (i / 4 & 1) == 0)) {
                    for (int k = 0; k < 4; k++) data[i + k] = random.nextFloat() * 2000.0f - 1000.0f;
                } else {
                    data[i] = 1.0f; data[i + 1] = 0.0f;
                    data[i + 2] = random.nextFloat() * 2.0f + 1.0f;
                    data[i + 3] = !distribution.equals("allFallbackZeroCross")
                            ? data[i + 2] * (random.nextFloat() - 0.5f) * 1.0e-6f : 0.0f;
                }
                boolean original = original(data[i], data[i + 1], data[i + 2], data[i + 3]);
                boolean candidate = guarded(data[i], data[i + 1], data[i + 2], data[i + 3]);
                if (original != candidate) throw new AssertionError("predicate output " + distribution);
                if (original) expectedHits++;
                if (AngleGuardMath.reject(data[i] * data[i + 3] - data[i + 1] * data[i + 2],
                        data[i] * data[i + 2] + data[i + 1] * data[i + 3])) bypasses++;
            }
            if (distribution.startsWith("allFallback") && bypasses != 0) throw new AssertionError("fallback distribution");
            int iterations = 64;
            for (int warm = 0; warm < 6; warm++) {
                boolean first = (warm & 1) == 1;
                run(data, first, adaptive, iterations); run(data, !first, adaptive, iterations);
                if (consumed != expectedHits * iterations) throw new AssertionError("warm consumption");
            }
            long[] baseline = new long[5], candidate = new long[5];
            for (int round = 0; round < 5; round++) {
                boolean first = (round & 1) == 1;
                long a = run(data, first, adaptive, iterations);
                if (consumed != expectedHits * iterations) throw new AssertionError("first result consumption");
                long b = run(data, !first, adaptive, iterations);
                if (consumed != expectedHits * iterations) throw new AssertionError("second result consumption");
                baseline[round] = first ? b : a; candidate[round] = first ? a : b;
                System.out.printf("ROUND distribution=%s round=%d evaluations=262144 nativeNs=%d guardedNs=%d%n",
                        distribution, round, baseline[round], candidate[round]);
            }
            Arrays.sort(baseline); Arrays.sort(candidate);
            System.out.printf("MEDIAN distribution=%s inputs=4096 bypasses=%d nativeNs=%d guardedNs=%d changePercent=%.3f%n",
                    distribution, bypasses, baseline[2], candidate[2], 100.0 * candidate[2] / baseline[2] - 100.0);
        }
        System.out.println("ANGLE_GUARD_COST_FINISHED consumed=" + consumed + " scope=SCALAR_ONLY hostGain=UNPROVEN");
    }
}
