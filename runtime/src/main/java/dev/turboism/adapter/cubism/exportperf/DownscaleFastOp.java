package dev.turboism.adapter.cubism.exportperf;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Provides bit-identical parallel premultiplied box downscaling matching {@code createReduced}.
 *
 * <p>Replaces the host's sequential double-nested box filter in {@code com.live2d.util.*.g}
 * while preserving exact integer truncation order and edge padding layout.</p>
 */
public final class DownscaleFastOp {

    private static final int PARALLEL_PIXEL_THRESHOLD = 262_144;
    private static final int ROW_STRIPE_SIZE = 64;
    private static final int ALPHA_MAX = 255;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;

    private DownscaleFastOp() {}

    /**
     * Downscales packed {@code TYPE_INT_ARGB} pixels into {@code dst} with optional border padding.
     *
     * @param scale integer downscale factor per axis
     * @param src source ARGB pixel buffer
     * @param srcWidth source image width
     * @param srcHeight source image height
     * @param dst destination ARGB pixel buffer
     * @param dstWidth unpadded destination width
     * @param dstHeight unpadded destination height
     * @param padding border padding in destination pixels
     */
    public static void downscaleBox(
            final int scale,
            final int[] src,
            final int srcWidth,
            final int srcHeight,
            final int[] dst,
            final int dstWidth,
            final int dstHeight,
            final int padding) {
        Arrays.fill(dst, 0);
        if (dstWidth <= 0 || dstHeight <= 0 || scale <= 0) {
            return;
        }
        final int stride = dstWidth + padding * 2;
        final int kernelArea = scale * scale;
        if ((long) srcWidth * srcHeight < PARALLEL_PIXEL_THRESHOLD || dstHeight < ROW_STRIPE_SIZE) {
            processBand(0, dstHeight, scale, kernelArea, src, srcWidth, srcHeight, dst, dstWidth, stride, padding);
            return;
        }
        final int bandCount = (dstHeight + ROW_STRIPE_SIZE - 1) / ROW_STRIPE_SIZE;
        IntStream.range(0, bandCount).parallel().forEach(band -> {
            final int yStart = band * ROW_STRIPE_SIZE;
            final int yEnd = Math.min(dstHeight, yStart + ROW_STRIPE_SIZE);
            processBand(yStart, yEnd, scale, kernelArea, src, srcWidth, srcHeight, dst, dstWidth, stride, padding);
        });
    }

    private static void processBand(
            final int yStart,
            final int yEnd,
            final int scale,
            final int kernelArea,
            final int[] src,
            final int srcWidth,
            final int srcHeight,
            final int[] dst,
            final int dstWidth,
            final int stride,
            final int padding) {
        final int maxSrcX = srcWidth - 1;
        final int maxSrcY = srcHeight - 1;
        for (int dy = yStart; dy < yEnd; dy++) {
            final int dstRowBase = (dy + padding) * stride + padding;
            final int sy0 = dy * scale;
            final int sy1 = Math.min(sy0 + scale - 1, maxSrcY);
            for (int dx = 0; dx < dstWidth; dx++) {
                final int sx0 = dx * scale;
                final int sx1 = Math.min(sx0 + scale - 1, maxSrcX);
                int sumA = 0;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;
                int sampleCount = 0;
                int weightSum = 0;
                for (int sy = sy0; sy <= sy1; sy++) {
                    final int srcRowBase = sy * srcWidth;
                    for (int sx = sx0; sx <= sx1; sx++) {
                        final int px = src[srcRowBase + sx];
                        final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                        final int r = (px >>> SHIFT_RED) & BYTE_MASK;
                        final int g = (px >>> SHIFT_GREEN) & BYTE_MASK;
                        final int b = px & BYTE_MASK;
                        sumA += a;
                        sumR += (r * a) / ALPHA_MAX;
                        sumG += (g * a) / ALPHA_MAX;
                        sumB += (b * a) / ALPHA_MAX;
                        sampleCount++;
                        weightSum += a;
                    }
                }
                final int outPixel;
                if (weightSum != 0 && sampleCount != 0) {
                    final int outA = sumA / kernelArea;
                    final int outR = (sumR * ALPHA_MAX) / weightSum;
                    final int outG = (sumG * ALPHA_MAX) / weightSum;
                    final int outB = (sumB * ALPHA_MAX) / weightSum;
                    outPixel = (outA << SHIFT_ALPHA) | (outR << SHIFT_RED) | (outG << SHIFT_GREEN) | outB;
                } else {
                    outPixel = 0;
                }
                dst[dstRowBase + dx] = outPixel;
            }
        }
    }
}
