import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

/** Offline hypothesis only: not a product hook and never packaged in the Agent. */
public final class WarpInteriorPrototype {
    private static volatile float sink;

    public static boolean interior(float[] src, float[] out, int count, int offset, int stride,
                                   float[] grid, int columns, int rows, boolean bilinear) {
        if (src == null || out == null || grid == null || grid == out || count < 0 || offset < 0 || stride < 2
                || count > 131072 || stride > 16 || columns < 1 || rows < 1
                || columns >= grid.length / 2 || rows >= grid.length / 2) return false;
        long endLong = (long) count * stride;
        long required = 2L * (columns + 1L) * (rows + 1L);
        if (endLong > Integer.MAX_VALUE || required > grid.length || endLong > src.length || endLong > out.length) return false;
        int end = (int) endLong;
        if (offset < end && (long) offset + ((end - 1L - offset) / stride) * stride + 1 >= end) return false;
        // Complete admission before any write, so a rejected call can safely execute the original.
        for (int i = offset; i < end; i += stride) {
            float x = src[i] * columns, y = src[i + 1] * rows;
            if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0 || x >= columns || y >= rows) return false;
        }
        int rowStep = 2 * (columns + 1);
        for (int i = offset; i < end; i += stride) {
            float x = src[i] * columns, y = src[i + 1] * rows;
            float u = x - (int) x, v = y - (int) y;
            int b = 2 * ((int) x + (int) y * (columns + 1));
            if (bilinear) {
                float a = 1f - u, c = 1f - v;
                out[i] = grid[b] * a * c + grid[b + 2] * u * c
                        + grid[b + rowStep] * a * v + grid[b + rowStep + 2] * u * v;
                out[i + 1] = grid[b + 1] * a * c + grid[b + 3] * u * c
                        + grid[b + rowStep + 1] * a * v + grid[b + rowStep + 3] * u * v;
            } else if (u + v < 1f) {
                out[i] = grid[b] * (1f - u - v) + grid[b + 2] * u + grid[b + rowStep] * v;
                out[i + 1] = grid[b + 1] * (1f - u - v) + grid[b + 3] * u + grid[b + rowStep + 1] * v;
            } else {
                out[i] = grid[b + rowStep + 2] * (u - 1f + v)
                        + grid[b + rowStep] * (1f - u) + grid[b + 2] * (1f - v);
                out[i + 1] = grid[b + rowStep + 3] * (u - 1f + v)
                        + grid[b + rowStep + 1] * (1f - u) + grid[b + 3] * (1f - v);
            }
        }
        return true;
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() != 17) throw new AssertionError("requires Linux JDK17");
        Path jar = Path.of(args[0]);
        try (var in = Files.newInputStream(jar)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
            if (!HexFormat.of().formatHex(digest.digest()).equals("988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21")) {
                throw new AssertionError("wrong native artifact");
            }
        }
        Class<?> nativeType = Class.forName("com.live2d.cubism.doc.model.deformer.warp.o");
        MethodType type = MethodType.methodType(void.class, float[].class, float[].class,
                int.class, int.class, int.class, float[].class, int.class, int.class, boolean.class);
        MethodHandle nativeCall = MethodHandles.publicLookup().findVirtual(nativeType, "a", type)
                .bindTo(nativeType.getField("a").get(null));
        MethodHandle candidate = MethodHandles.lookup().findStatic(WarpInteriorPrototype.class, "interior",
                type.changeReturnType(boolean.class));
        Random random = new Random(20260907);
        long compared = 0;
        for (int trial = 0; trial < 4000; trial++) {
            int columns = 1 + random.nextInt(16), rows = 1 + random.nextInt(16);
            int count = random.nextInt(257), stride = 2 + random.nextInt(3), offset = random.nextInt(stride - 1);
            float[] src = new float[count * stride], grid = new float[2 * (columns + 1) * (rows + 1)];
            for (int i = 0; i < src.length; i++) src[i] = random.nextFloat() * 0.999f;
            if (src.length > 1 && trial % 8 == 0) { src[0] = -0.0f; src[1] = 0f; }
            for (int i = 0; i < grid.length; i++) grid[i] = (random.nextFloat() - 0.5f) * 10000;
            boolean bilinear = trial % 2 == 0;
            float[] expected = trial % 3 == 0 ? src.clone() : filled(src.length);
            float[] actual = expected.clone();
            float[] nativeSource = trial % 3 == 0 ? expected : src;
            float[] candidateSource = trial % 3 == 0 ? actual : src;
            nativeCall.invokeExact(nativeSource, expected, count, offset, stride, grid, columns, rows, bilinear);
            if (!interior(candidateSource, actual, count, offset, stride, grid, columns, rows, bilinear)) throw new AssertionError("valid interior rejected");
            for (int i = 0; i < expected.length; i++) {
                if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) throw new AssertionError("raw mismatch trial=" + trial + " index=" + i);
                compared++;
            }
        }
        float[] grid = new float[8];
        for (float bad : new float[]{-1, 1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] src = {0.5f, 0.5f, bad, 0.5f}, out = filled(4), before = out.clone();
            if (interior(src, out, 2, 0, 2, grid, 1, 1, true) || !Arrays.equals(out, before)) throw new AssertionError("unsafe fallback");
        }
        if (interior(new float[2], grid, 1, 0, 2, grid, 1, 1, true)
                || interior(new float[2], new float[2], Integer.MAX_VALUE, 0, 2, grid, 1, 1, true)
                || interior(null, new float[2], 1, 0, 2, grid, 1, 1, true)) throw new AssertionError("invalid admission");
        System.out.println("differential=PASS trials=4000 rawValues=" + compared + " fallback=PASS");
        for (int points : new int[]{64, 1024, 16384}) {
            float[] src = new float[points * 2], out = new float[points * 2], mesh = new float[2 * 17 * 17];
            for (int i = 0; i < src.length; i++) src[i] = random.nextFloat() * 0.999f;
            for (int i = 0; i < mesh.length; i++) mesh[i] = random.nextFloat();
            int repeats = Math.max(100, 2_000_000 / points);
            for (int i = 0; i < 5000; i++) {
                nativeCall.invokeExact(src, out, points, 0, 2, mesh, 16, 16, true);
                boolean accepted = (boolean) candidate.invokeExact(src, out, points, 0, 2, mesh, 16, 16, true);
                if (!accepted) throw new AssertionError();
            }
            double[] oldTimes = new double[7], newTimes = new double[7];
            for (int round = 0; round < 7; round++) {
                for (int slot = 0; slot < 2; slot++) {
                    boolean fast = (round + slot) % 2 == 1;
                    long start = System.nanoTime();
                    for (int n = 0; n < repeats; n++) {
                        if (fast) {
                            boolean accepted = (boolean) candidate.invokeExact(src, out, points, 0, 2, mesh, 16, 16, true);
                            if (!accepted) throw new AssertionError();
                        } else nativeCall.invokeExact(src, out, points, 0, 2, mesh, 16, 16, true);
                    }
                    double nsPerPoint = (System.nanoTime() - start) / (double) repeats / points;
                    (fast ? newTimes : oldTimes)[round] = nsPerPoint;
                    sink = out[out.length - 1];
                }
            }
            System.out.println("points=" + points + " nativeNsPerPoint=" + Arrays.toString(oldTimes) + " candidateNsPerPoint=" + Arrays.toString(newTimes));
            Arrays.sort(oldTimes); Arrays.sort(newTimes);
            System.out.println("median points=" + points + " native=" + oldTimes[3] + " candidate=" + newTimes[3] + " ratio=" + newTimes[3] / oldTimes[3]);
        }
        System.out.println("sink=" + sink + "; OFFLINE_ONLY_NOT_PRIMARY_METRIC_EVIDENCE");
    }
    private static float[] filled(int n) { float[] a = new float[n]; Arrays.fill(a, -9876.5f); return a; }
}
