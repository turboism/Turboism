package dev.turboism.validation;

import dev.turboism.adapter.cubism.optimization.image.CanonicalRgbaImage;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Function;

/** Offline conversion only. No GL, decode, end-to-end loading or retained-memory claims. */
public final class CanonicalRgbaImageBenchmark {
    private static final ComponentColorModel RGBA = new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[] {8, 8, 8, 8},
            true, true, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
    private static final byte[] TABLE = table();
    private static volatile BufferedImage sink;
    private static final com.sun.management.ThreadMXBean ALLOCATIONS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private CanonicalRgbaImageBenchmark() {}

    public static void main(String[] args) {
        System.out.println("runtime=" + System.getProperty("java.runtime.version"));
        if (ALLOCATIONS.isThreadAllocatedMemorySupported()) ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        System.out.println("type,size,cropped,legacy_ms,fast_ms,speedup,legacy_bytes,fast_bytes");
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            for (int[] size : new int[][] {{64, 64}, {1024, 1024}, {3840, 5056}}) {
                for (boolean cropped : new boolean[] {false, true}) run(type, size[0], size[1], cropped);
            }
        }
    }

    private static void run(int type, int width, int height, boolean cropped) {
        var parent = new BufferedImage(width + (cropped ? 17 : 0), height + (cropped ? 9 : 0), type);
        var random = new Random(42);
        int[] row = new int[parent.getWidth()];
        for (int y = 0; y < parent.getHeight(); y++) {
            for (int x = 0; x < row.length; x++) row[x] = random.nextInt();
            parent.setRGB(0, y, row.length, 1, row, 0, row.length);
        }
        var source = cropped ? parent.getSubimage(7, 3, width, height) : parent;
        if (!Arrays.equals(bytes(legacy(source)), bytes(CanonicalRgbaImage.prepare(source))))
            throw new AssertionError("pixel mismatch");
        int iterations = Math.max(1, (int) (4_194_304L / ((long) width * height)));
        iterations = Math.min(iterations, 1024);
        for (int i = 0; i < 10; i++) {
            measure(source, CanonicalRgbaImageBenchmark::legacy, iterations);
            measure(source, CanonicalRgbaImage::prepare, iterations);
        }
        double[] oldTimes = new double[9], newTimes = new double[9];
        long oldBytes = -1, newBytes = -1;
        for (int i = 0; i < 9; i++) {
            // Alternate order; no forced GC inside or between measurements.
            Measurement first = measure(source, i % 2 == 0 ? CanonicalRgbaImageBenchmark::legacy
                    : CanonicalRgbaImage::prepare, iterations);
            Measurement second = measure(source, i % 2 == 0 ? CanonicalRgbaImage::prepare
                    : CanonicalRgbaImageBenchmark::legacy, iterations);
            Measurement old = i % 2 == 0 ? first : second, fast = i % 2 == 0 ? second : first;
            oldTimes[i] = old.ns() / 1e6;
            newTimes[i] = fast.ns() / 1e6;
            oldBytes = old.bytes();
            newBytes = fast.bytes();
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        System.out.printf(java.util.Locale.ROOT, "%s,%dx%d,%s,%.4f,%.4f,%.2f,%d,%d%n",
                type == BufferedImage.TYPE_INT_ARGB ? "ARGB" : "ABGR", width, height, cropped,
                oldTimes[4], newTimes[4], oldTimes[4] / newTimes[4], oldBytes, newBytes);
    }

    private static Measurement measure(BufferedImage source, Function<BufferedImage, BufferedImage> operation,
                                       int iterations) {
        long thread = Thread.currentThread().getId();
        long before = allocated(thread), start = System.nanoTime();
        for (int i = 0; i < iterations; i++) sink = operation.apply(source);
        long elapsed = System.nanoTime() - start, after = allocated(thread);
        return new Measurement((double) elapsed / iterations,
                before < 0 || after < 0 ? -1 : (after - before) / iterations);
    }

    private static long allocated(long thread) {
        return ALLOCATIONS.isThreadAllocatedMemorySupported()
                ? ALLOCATIONS.getThreadAllocatedBytes(thread) : -1;
    }

    // Frozen pre-fast-path implementation, including destination admission and row scratch allocation.
    private static BufferedImage legacy(BufferedImage source) {
        if (source == null || source.getClass() != BufferedImage.class) return null;
        int type = source.getType();
        if ((type != BufferedImage.TYPE_INT_ARGB && type != BufferedImage.TYPE_4BYTE_ABGR)
                || source.isAlphaPremultiplied()
                || !source.getColorModel().getColorSpace().isCS_sRGB()) return null;
        int width = source.getWidth(), height = source.getHeight();
        long pixels = (long) width * height;
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || pixels < 4096 || pixels > 33_554_432L)
            return null;
        var raster = RGBA.createCompatibleWritableRaster(width, height);
        if (!(raster.getSampleModel() instanceof PixelInterleavedSampleModel model)
                || model.getPixelStride() != 4
                || model.getScanlineStride() != width * 4
                || !Arrays.equals(model.getBandOffsets(), new int[] {0, 1, 2, 3})
                || !(raster.getDataBuffer() instanceof DataBufferByte data)
                || data.getNumBanks() != 1
                || data.getOffset() != 0) return null;
        byte[] rgba = data.getData();
        if (rgba.length != pixels * 4) return null;
        int[] row = new int[width];
        int destination = 0;
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) {
                int alpha = argb >>> 24, base = alpha << 8;
                rgba[destination++] = TABLE[base | ((argb >>> 16) & 255)];
                rgba[destination++] = TABLE[base | ((argb >>> 8) & 255)];
                rgba[destination++] = TABLE[base | (argb & 255)];
                rgba[destination++] = (byte) alpha;
            }
        }
        return new BufferedImage(RGBA, raster, true, null);
    }

    private static byte[] table() {
        byte[] result = new byte[65536], pixel = new byte[4];
        for (int a = 0; a < 256; a++)
            for (int c = 0; c < 256; c++) {
                pixel = (byte[]) RGBA.getDataElements((a << 24) | (c << 16) | (c << 8) | c, pixel);
                result[(a << 8) | c] = pixel[0];
            }
        return result;
    }

    private static byte[] bytes(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    private record Measurement(double ns, long bytes) {}
}
