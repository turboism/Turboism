package dev.turboism.adapter.cubism.optimization.psd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.exportperf.DownscaleFastOp;
import dev.turboism.adapter.cubism.exportperf.EdgeCleanFastOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PsdOptimizationParityTest {

    @Test
    void decodesPackBitsRleBitIdenticallyIncludingZeroAndMinus128AndConcurrentStress() throws Exception {
        final int width = 310;
        final int height = 19;
        final int channels = 3;
        final byte[][] expectedPlanes = new byte[channels][width * height];
        final int[][] expectedScanlineLens = new int[channels][height];
        final List<Integer> expectedHeaders = new ArrayList<>();

        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final int[] scanLengths = new int[channels * height];
        final List<byte[]> encodedRows = new ArrayList<>();
        final Random rng = new Random(0x50534452L);

        int idx = 0;
        for (int ch = 0; ch < channels; ch++) {
            for (int y = 0; y < height; y++) {
                final ByteArrayOutputStream rowRle = new ByteArrayOutputStream();
                int filled = 0;
                // Exercise op == -128 (repeat 129), op == 0 (repeat 1), positive literals, and short tails.
                if (y == 0) {
                    expectedHeaders.add(-128);
                    rowRle.write(-128 & 0xFF);
                    rowRle.write(0x5A);
                    Arrays.fill(expectedPlanes[ch], y * width, y * width + 129, (byte) 0x5A);
                    filled += 129;

                    expectedHeaders.add(0);
                    rowRle.write(0);
                    rowRle.write(0x7F);
                    expectedPlanes[ch][y * width + filled] = (byte) 0x7F;
                    filled += 1;
                }
                while (filled < width) {
                    final int remaining = width - filled;
                    if (rng.nextBoolean() && remaining >= 2) {
                        final int rep = Math.min(remaining, 1 + rng.nextInt(16));
                        final int header = -(rep - 1);
                        final byte val = (byte) rng.nextInt();
                        expectedHeaders.add(header);
                        rowRle.write(header & 0xFF);
                        rowRle.write(val & 0xFF);
                        Arrays.fill(expectedPlanes[ch], y * width + filled, y * width + filled + rep, val);
                        filled += rep;
                    } else {
                        final int lit = Math.min(remaining, 2 + rng.nextInt(20));
                        final int header = lit - 1;
                        expectedHeaders.add(header);
                        rowRle.write(header);
                        for (int k = 0; k < lit; k++) {
                            final byte b = (byte) rng.nextInt();
                            rowRle.write(b & 0xFF);
                            expectedPlanes[ch][y * width + filled + k] = b;
                        }
                        filled += lit;
                    }
                }
                final byte[] rowBytes = rowRle.toByteArray();
                scanLengths[idx++] = rowBytes.length;
                expectedScanlineLens[ch][y] = rowBytes.length;
                encodedRows.add(rowBytes);
            }
        }

        // Write header offset prefix + scanline length table + compressed scanlines + trailing guard bytes.
        final int prefixPad = 13;
        for (int i = 0; i < prefixPad; i++) {
            stream.write(0x2A);
        }
        for (final int len : scanLengths) {
            stream.write((len >>> 8) & 0xFF);
            stream.write(len & 0xFF);
        }
        for (final byte[] row : encodedRows) {
            stream.write(row);
        }
        stream.write(0x11);
        stream.write(0x22);
        final byte[] streamBytes = stream.toByteArray();

        final byte[][] actualMerged = new byte[channels][width * height];
        final PsdRleBridge.DecodeResult mergedResult =
                PsdRleBridge.decodeChannels(streamBytes, prefixPad, width, height, channels, actualMerged);
        assertNotNull(mergedResult);
        assertEquals(streamBytes.length - 2, mergedResult.nextOffset());
        assertArrayEquals(scanLengths, mergedResult.scanlineLengths());
        assertArrayEquals(expectedHeaders.stream().mapToInt(Integer::intValue).toArray(), mergedResult.rleHeaders());
        for (int ch = 0; ch < channels; ch++) {
            assertArrayEquals(expectedPlanes[ch], actualMerged[ch]);
        }

        // Verify concurrent RLE calls never race on any shared mutable header buffer.
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final CountDownLatch gate = new CountDownLatch(1);
            final List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                tasks.add(() -> {
                    gate.await();
                    for (int iter = 0; iter < 50; iter++) {
                        final byte[][] out = new byte[channels][width * height];
                        final PsdRleBridge.DecodeResult res =
                                PsdRleBridge.decodeChannels(streamBytes, prefixPad, width, height, channels, out);
                        assertNotNull(res);
                        assertArrayEquals(mergedResult.rleHeaders(), res.rleHeaders());
                        for (int ch = 0; ch < channels; ch++) {
                            assertArrayEquals(expectedPlanes[ch], out[ch]);
                        }
                    }
                    return null;
                });
            }
            gate.countDown();
            final List<Future<Void>> futures = pool.invokeAll(tasks);
            for (final Future<Void> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Truncated or under-decoded streams must cleanly return null for sequential fallback.
        assertNull(PsdRleBridge.decodeChannels(
                Arrays.copyOf(streamBytes, streamBytes.length - 5),
                prefixPad,
                width,
                height,
                channels,
                new byte[channels][width * height]));
    }

    @Test
    void edgeFilterBlurDownscaleAndThreadSafeEdgeCleanMatchHostBitIdentically() throws Exception {
        final int w = 77;
        final int h = 53;
        final int[] argb = new int[w * h];
        final Random rng = new Random(0xED6E1234L);
        for (int i = 0; i < argb.length; i++) {
            argb[i] = rng.nextInt();
        }

        final int[] laplacian = {1, 1, 1, 1, -8, 1, 1, 1, 1};
        final byte[] actualEdge = new byte[w * h];
        assertTrue(PsdImageOps.convolveEdgeGray(argb, actualEdge, w, h, laplacian, 3, 1));
        assertArrayEquals(referenceEdgeConvolve(argb, w, h, laplacian, 3, 1), actualEdge);

        final int[] customKernel = {2, -1, 0, 1, -5, 3, 0, 2, -1};
        final byte[] actualCustomEdge = new byte[w * h];
        assertTrue(PsdImageOps.convolveEdgeGray(argb, actualCustomEdge, w, h, customKernel, 3, 3));
        assertArrayEquals(referenceEdgeConvolve(argb, w, h, customKernel, 3, 3), actualCustomEdge);

        final int[] boxKernel = {1, 1, 1, 1, 1, 1, 1, 1, 1};
        final byte[] actualBlur = new byte[w * h];
        PsdImageOps.blurGray3x3(actualEdge, actualBlur, w, h, boxKernel, 9);
        assertArrayEquals(referenceBlur3x3(actualEdge, w, h, boxKernel, 9), actualBlur);

        final int[] weightedBlur = {1, 2, 1, 2, 4, 2, 1, 2, 1};
        final byte[] actualWeightedBlur = new byte[w * h];
        PsdImageOps.blurGray3x3(actualEdge, actualWeightedBlur, w, h, weightedBlur, 16);
        assertArrayEquals(referenceBlur3x3(actualEdge, w, h, weightedBlur, 16), actualWeightedBlur);

        // Check DownscaleFastOp parity for Step001 reduced geometry.
        final int scale = 4;
        final int dstW = w / scale + (w % scale != 0 ? 1 : 0);
        final int dstH = h / scale + (h % scale != 0 ? 1 : 0);
        final int margin = 2;
        final int[] downscaled = new int[(dstW + margin * 2) * (dstH + margin * 2)];
        DownscaleFastOp.downscaleBox(scale, argb, w, h, downscaled, dstW, dstH, margin);
        assertEquals(0, downscaled[0]);

        // Check concurrent cleanEdgesThreadSafe matches sequential EdgeCleanFastOp bit-for-bit.
        final BufferedImage srcImg = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        final int[] srcData = ((DataBufferInt) srcImg.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < srcData.length; i++) {
            srcData[i] = (i % 7 == 0) ? 0x00102030 : (0x80000000 | (i * 0x010101 & 0x00FFFFFF));
        }
        final BufferedImage seqClean = EdgeCleanFastOp.fastCleanEdges(srcImg, 3, false);
        assertNotNull(seqClean);
        final int[] seqCleanPixels = ((DataBufferInt) seqClean.getRaster().getDataBuffer()).getData();
        final BufferedImage concurrentClean = PsdImageOps.cleanEdgesThreadSafe(srcImg, 3, false);
        assertNotNull(concurrentClean);
        assertArrayEquals(
                seqCleanPixels, ((DataBufferInt) concurrentClean.getRaster().getDataBuffer()).getData());
    }

    @Test
    void largestFirstLayerOrderingPrioritizesLargestBytePayloadsDeterministically() {
        final int[] offsets = {10, 110, 2110, 2120, 52120};
        final int[] order = PsdLayerDecodeBridge.sortIndicesByPayloadDesc(offsets, 4);
        assertArrayEquals(new int[] {3, 1, 0, 2}, order);
    }

    private static byte[] referenceEdgeConvolve(
            final int[] src, final int width, final int height, final int[] kernel, final int size, final int div) {
        final byte[] out = new byte[width * height];
        final int half = size / 2;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int rSum = 0;
                int gSum = 0;
                int bSum = 0;
                for (int k = 0; k < size * size; k++) {
                    final int sx = x + half - (k % size);
                    final int sy = y + half - (k / size);
                    final int weight = kernel[k];
                    final int px = src[sy * width + sx];
                    rSum += weight * ((px >>> 16) & 0xFF);
                    gSum += weight * ((px >>> 8) & 0xFF);
                    bSum += weight * (px & 0xFF);
                }
                final int v = (Math.abs(rSum) / div + Math.abs(gSum) / div + Math.abs(bSum) / div) / 3;
                out[y * width + x] = (byte) v;
            }
        }
        return out;
    }

    private static byte[] referenceBlur3x3(
            final byte[] src, final int width, final int height, final int[] kernel, final int div) {
        final byte[] out = new byte[width * height];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int sum = 0;
                for (int k = 0; k < 9; k++) {
                    final int sx = x + 1 - (k % 3);
                    final int sy = y + 1 - (k / 3);
                    sum += kernel[k] * (src[sy * width + sx] & 0xFF);
                }
                out[y * width + x] = (byte) (sum / div);
            }
        }
        return out;
    }
}
