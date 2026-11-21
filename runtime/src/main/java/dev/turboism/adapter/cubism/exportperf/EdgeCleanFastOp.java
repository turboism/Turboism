package dev.turboism.adapter.cubism.exportperf;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Provides thread-safe, bit-identical fast edge cleaning for {@code jp.noids.graphics.h.a}.
 *
 * <p>Replaces 49 full-frame {@code Graphics2D.drawImage} passes and shared static median buffers
 * with direct packed-int operations, falling back to native Java2D only on a compact strip of
 * non-occluded semi-transparent pixels to guarantee exact rounding parity.</p>
 */
public final class EdgeCleanFastOp {

    private static final int FILL_GREY = 0x00808080;
    private static final int FILL_WHITE = 0x00FFFFFF;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int BYTE_MASK = 0xFF;
    private static final int ALPHA_OPAQUE = 255;
    private static final int LOW_ALPHA_PRUNE_THRESHOLD = 5;
    private static final int NEIGHBOR_MAX_ALPHA_THRESHOLD = 50;
    private static final int UNBLEND_ALPHA_CUTOFF = 64;
    private static final float HALF_ROUND = 0.5f;
    private static final int MEDIAN_WINDOW_LEN = 9;
    private static final int MEDIAN_MID_INDEX = 4;
    private static final int STRIP_WIDTH = 4096;
    private static final int GREY_RAMP_THRESHOLD = 10;
    private static final int PARALLEL_PIXEL_THRESHOLD = 65_536;
    private static final int PARALLEL_ROW_CHUNK = 64;
    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;

    private static final ThreadLocal<MedianScratch> MEDIAN_SCRATCH = ThreadLocal.withInitial(MedianScratch::new);

    private EdgeCleanFastOp() {}

    /**
     * Cleans matte fringes on {@code source} using spiral dilation, median smoothing, and unblend.
     *
     * @param source input {@code BufferedImage}
     * @param radius spiral dilation radius in pixels
     * @param greyFill {@code true} to initialize transparent background grey, {@code false} white
     * @return cleaned {@code TYPE_INT_ARGB} image, or {@code null} when fallback to host is needed
     */
    public static BufferedImage fastCleanEdges(final BufferedImage source, final int radius, final boolean greyFill) {
        if (source == null || source.getType() != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (width <= 0 || height <= 0 || radius <= 0) {
            return null;
        }
        final DataBuffer buffer = source.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt dataBuffer)
                || dataBuffer.getNumBanks() != 1
                || dataBuffer.getOffset() != 0) {
            return null;
        }
        final int[] src = dataBuffer.getData();
        if ((long) width * height > src.length) {
            return null;
        }
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        cleanPixels(src, width, height, radius, greyFill, out);
        return result;
    }

    /**
     * Executes bit-identical edge cleaning on raw packed {@code 0xAARRGGBB} pixel arrays.
     *
     * @param src input packed ARGB pixels
     * @param width image width in pixels
     * @param height image height in pixels
     * @param radius spiral dilation radius
     * @param greyFill background RGB mode matching host boolean flag
     * @param out preallocated output buffer of at least {@code width * height} ints
     */
    public static void cleanPixels(
            final int[] src,
            final int width,
            final int height,
            final int radius,
            final boolean greyFill,
            final int[] out) {
        final int pixelCount = width * height;
        final int[] offsets = buildInwardSpiralOffsets(radius);
        final int fillColor = greyFill ? FILL_GREY : FILL_WHITE;
        final int[] tmp = new int[pixelCount];

        dilateSpiral(src, width, height, offsets, fillColor, tmp);
        applyStockAlphaMask(src, tmp, width, height);
        if (greyFill) {
            applyGreyRamp(tmp, width, height);
        }
        applyMedianAndUnblend(tmp, width, height, out);
    }

    private static void applyGreyRamp(final int[] pixels, final int width, final int height) {
        for (int y = 0; y < height; y++) {
            int val = 0;
            for (int x = 0; x < width; x++) {
                val = stepGreyRamp(pixels, x, y, width, val);
            }
            val = 0;
            for (int x = width - 1; x >= 0; x--) {
                val = stepGreyRamp(pixels, x, y, width, val);
            }
        }
        for (int x = 0; x < width; x++) {
            int val = 0;
            for (int y = 0; y < height; y++) {
                val = stepGreyRamp(pixels, x, y, width, val);
            }
            val = 0;
            for (int y = height - 1; y >= 0; y--) {
                val = stepGreyRamp(pixels, x, y, width, val);
            }
        }
    }

    private static int stepGreyRamp(final int[] pixels, final int x, final int y, final int width, int val) {
        final int idx = y * width + x;
        final int cur = pixels[idx];
        final int curA = (cur >>> SHIFT_ALPHA) & BYTE_MASK;
        val--;
        if (curA < GREY_RAMP_THRESHOLD) {
            if (curA < val) {
                final int nextA = Math.max(1, val);
                pixels[idx] = (nextA << SHIFT_ALPHA) | (cur & RGB_MASK);
            } else if (curA == 0) {
                pixels[idx] = (1 << SHIFT_ALPHA) | (cur & RGB_MASK);
            }
        }
        if (val < curA) {
            val = curA;
        }
        if (val > GREY_RAMP_THRESHOLD) {
            val = GREY_RAMP_THRESHOLD;
        }
        return val;
    }

    private static int[] buildInwardSpiralOffsets(final int radius) {
        final int[] offsets = new int[(radius * 2 + 3) * (radius * 2 + 3) * 2];
        int pos = 0;
        int xMin = -radius;
        int xMax = radius;
        int yMin = -radius;
        int yMax = radius;
        int x = xMin;
        int y = yMin;
        yMin++;
        while (true) {
            for (; x <= xMax; x++) {
                offsets[pos++] = x;
                offsets[pos++] = y;
            }
            if (yMin == yMax && xMin == xMax) {
                break;
            }
            xMax--;
            for (; y <= yMax; y++) {
                offsets[pos++] = x;
                offsets[pos++] = y;
            }
            if (yMin == yMax && xMin == xMax) {
                break;
            }
            yMax--;
            for (; x >= xMin; x--) {
                offsets[pos++] = x;
                offsets[pos++] = y;
            }
            if (yMin == yMax && xMin == xMax) {
                break;
            }
            xMin++;
            for (; y >= yMin; y--) {
                offsets[pos++] = x;
                offsets[pos++] = y;
            }
            if (yMin == yMax && xMin == xMax) {
                break;
            }
            yMin++;
        }
        return Arrays.copyOf(offsets, pos);
    }

    private static void dilateSpiral(
            final int[] src,
            final int width,
            final int height,
            final int[] offsets,
            final int fillColor,
            final int[] tmp) {
        final int layerCount = offsets.length / 2;
        final int pixelCount = width * height;
        final int[] semiIndices = new int[pixelCount];
        final int[] semiStartLayer = new int[pixelCount];
        int semiCount = 0;

        for (int y = 0; y < height; y++) {
            final int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                final int idx = rowBase + x;
                int basePixel = fillColor;
                int firstSemiLayer = -1;
                int prevLayerIdx = -1;
                for (int layer = layerCount - 1; layer >= 0; layer--) {
                    final int sx = x - offsets[layer * 2];
                    final int sy = y - offsets[layer * 2 + 1];
                    if (sx < 0 || sx >= width || sy < 0 || sy >= height) {
                        continue;
                    }
                    final int spx = src[sy * width + sx];
                    final int sa = (spx >>> SHIFT_ALPHA) & BYTE_MASK;
                    if (sa == 0) {
                        continue;
                    }
                    if (sa == ALPHA_OPAQUE) {
                        basePixel = spx;
                        if (prevLayerIdx >= 0) {
                            firstSemiLayer = prevLayerIdx;
                        }
                        break;
                    }
                    prevLayerIdx = layer;
                    firstSemiLayer = layer;
                }
                if (firstSemiLayer >= 0) {
                    tmp[idx] = basePixel;
                    semiIndices[semiCount] = idx;
                    semiStartLayer[semiCount] = firstSemiLayer;
                    semiCount++;
                } else {
                    tmp[idx] = basePixel;
                }
            }
        }

        if (semiCount > 0) {
            compositeSemiTransparentViaJava2d(
                    src, width, height, offsets, layerCount, tmp, semiIndices, semiStartLayer, semiCount);
        }
    }

    private static void compositeSemiTransparentViaJava2d(
            final int[] src,
            final int width,
            final int height,
            final int[] offsets,
            final int layerCount,
            final int[] tmp,
            final int[] semiIndices,
            final int[] semiStartLayer,
            final int semiCount) {
        final int stripW = Math.min(semiCount, STRIP_WIDTH);
        final int stripH = (semiCount + stripW - 1) / stripW;
        final BufferedImage dstImg = new BufferedImage(stripW, stripH, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage layerImg = new BufferedImage(stripW, stripH, BufferedImage.TYPE_INT_ARGB);
        final int[] dstBuf = ((DataBufferInt) dstImg.getRaster().getDataBuffer()).getData();
        final int[] layerBuf = ((DataBufferInt) layerImg.getRaster().getDataBuffer()).getData();

        int minStartLayer = layerCount;
        for (int i = 0; i < semiCount; i++) {
            dstBuf[i] = tmp[semiIndices[i]];
            if (semiStartLayer[i] < minStartLayer) {
                minStartLayer = semiStartLayer[i];
            }
        }

        final Graphics2D g = dstImg.createGraphics();
        try {
            for (int layer = minStartLayer; layer < layerCount; layer++) {
                final int dx = offsets[layer * 2];
                final int dy = offsets[layer * 2 + 1];
                boolean anyNonZero = false;
                for (int i = 0; i < semiCount; i++) {
                    if (layer < semiStartLayer[i]) {
                        layerBuf[i] = 0;
                        continue;
                    }
                    final int dstIdx = semiIndices[i];
                    final int sx = (dstIdx % width) - dx;
                    final int sy = (dstIdx / width) - dy;
                    if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                        final int px = src[sy * width + sx];
                        layerBuf[i] = px;
                        if (((px >>> SHIFT_ALPHA) & BYTE_MASK) != 0) {
                            anyNonZero = true;
                        }
                    } else {
                        layerBuf[i] = 0;
                    }
                }
                if (anyNonZero) {
                    g.drawImage(layerImg, 0, 0, null);
                }
            }
        } finally {
            g.dispose();
            layerImg.flush();
            dstImg.flush();
        }

        for (int i = 0; i < semiCount; i++) {
            tmp[semiIndices[i]] = dstBuf[i];
        }
    }

    /**
     * Preserves the host's exact coordinate mapping ({@code i % width}, {@code i / height}) in
     * {@code jp.noids.graphics.h.a(int[], int[], int, int)} for non-rectangular dimensions.
     */
    private static void applyStockAlphaMask(final int[] src, final int[] tmp, final int width, final int height) {
        final int pixelCount = src.length;
        for (int i = 0; i < pixelCount; i++) {
            final int spx = src[i];
            final int a = (spx >>> SHIFT_ALPHA) & BYTE_MASK;
            if (a == 0) {
                tmp[i] &= RGB_MASK;
            } else if (a < LOW_ALPHA_PRUNE_THRESHOLD
                    && maxEightNeighborAlpha(src, i % width, i / height, width, height)
                            > NEIGHBOR_MAX_ALPHA_THRESHOLD) {
                tmp[i] &= RGB_MASK;
            } else {
                tmp[i] = spx;
            }
        }
    }

    private static int maxEightNeighborAlpha(
            final int[] src, final int cx, final int cy, final int width, final int height) {
        int maxAlpha = 0;
        for (int y = cy - 1; y <= cy + 1; y++) {
            if (y < 0 || y >= height) {
                continue;
            }
            final int rowBase = y * width;
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (x < 0 || x >= width) {
                    continue;
                }
                if (x == cx && y == cy) {
                    continue;
                }
                final int neighborAlpha = (src[rowBase + x] >>> SHIFT_ALPHA) & BYTE_MASK;
                if (neighborAlpha > maxAlpha) {
                    maxAlpha = neighborAlpha;
                }
            }
        }
        return maxAlpha;
    }

    private static void applyMedianAndUnblend(final int[] tmp, final int width, final int height, final int[] out) {
        System.arraycopy(tmp, 0, out, 0, tmp.length);
        if (height <= 2 || width <= 2) {
            return;
        }
        final int[] medianOut = new int[tmp.length];
        System.arraycopy(tmp, 0, medianOut, 0, tmp.length);
        if ((long) width * height < PARALLEL_PIXEL_THRESHOLD || height < PARALLEL_ROW_CHUNK) {
            computeMedianRows(1, height - 1, tmp, width, medianOut);
            applyStockUnblendRange(0, tmp.length, tmp, medianOut, out);
            return;
        }
        final int rowCount = height - 2;
        final int bandCount = (rowCount + PARALLEL_ROW_CHUNK - 1) / PARALLEL_ROW_CHUNK;
        IntStream.range(0, bandCount).parallel().forEach(band -> {
            final int y0 = 1 + band * PARALLEL_ROW_CHUNK;
            final int y1 = Math.min(height - 1, y0 + PARALLEL_ROW_CHUNK);
            computeMedianRows(y0, y1, tmp, width, medianOut);
        });
        applyStockUnblendRange(0, tmp.length, tmp, medianOut, out);
    }

    private static void computeMedianRows(
            final int yStart, final int yEnd, final int[] tmp, final int width, final int[] medianOut) {
        final MedianScratch scratch = MEDIAN_SCRATCH.get();
        final int[] chA = scratch.a;
        final int[] chR = scratch.r;
        final int[] chG = scratch.g;
        final int[] chB = scratch.b;
        for (int y = yStart; y < yEnd; y++) {
            final int rowBase = y * width;
            for (int x = 1; x < width - 1; x++) {
                final int centerIdx = rowBase + x;
                medianOut[centerIdx] = computeMedianIfMixed(tmp, x, y, width, chA, chR, chG, chB);
            }
        }
    }

    private static void applyStockUnblendRange(
            final int start, final int end, final int[] tmp, final int[] medianOut, final int[] out) {
        final float cutoffF = UNBLEND_ALPHA_CUTOFF;
        for (int i = start; i < end; i++) {
            final int center = tmp[i];
            final int centerA = (center >>> SHIFT_ALPHA) & BYTE_MASK;
            if (centerA == 0) {
                out[i] = medianOut[i] & RGB_MASK;
            } else if (centerA >= UNBLEND_ALPHA_CUTOFF) {
                out[i] = center;
            } else {
                final int median = medianOut[i];
                final float t = centerA / cutoffF;
                final float invT = 1.0f - t;
                final int sr = (center >>> SHIFT_RED) & BYTE_MASK;
                final int sg = (center >>> SHIFT_GREEN) & BYTE_MASK;
                final int sb = center & BYTE_MASK;
                final int dr = (median >>> SHIFT_RED) & BYTE_MASK;
                final int dg = (median >>> SHIFT_GREEN) & BYTE_MASK;
                final int db = median & BYTE_MASK;
                final int r = ((int) (t * sr + invT * dr + HALF_ROUND)) & BYTE_MASK;
                final int g = ((int) (t * sg + invT * dg + HALF_ROUND)) & BYTE_MASK;
                final int b = ((int) (t * sb + invT * db + HALF_ROUND)) & BYTE_MASK;
                out[i] = (centerA << SHIFT_ALPHA) | (r << SHIFT_RED) | (g << SHIFT_GREEN) | b;
            }
        }
    }

    private static int computeMedianIfMixed(
            final int[] tmp,
            final int cx,
            final int cy,
            final int width,
            final int[] chA,
            final int[] chR,
            final int[] chG,
            final int[] chB) {
        int maxA = 0;
        int minA = ALPHA_OPAQUE;
        int k = 0;
        for (int ny = cy - 1; ny <= cy + 1; ny++) {
            final int nRow = ny * width;
            for (int nx = cx - 1; nx < cx + 1; nx++) {
                final int p = tmp[nRow + nx];
                final int a = (p >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a < minA) {
                    minA = a;
                }
                if (a > maxA) {
                    maxA = a;
                }
                chA[k] = a;
                chR[k] = (p >>> SHIFT_RED) & BYTE_MASK;
                chG[k] = (p >>> SHIFT_GREEN) & BYTE_MASK;
                chB[k] = p & BYTE_MASK;
                k++;
            }
        }
        if (maxA == 0 || minA == ALPHA_OPAQUE) {
            return tmp[cy * width + cx];
        }
        Arrays.sort(chA);
        Arrays.sort(chR);
        Arrays.sort(chG);
        Arrays.sort(chB);
        return (chA[MEDIAN_MID_INDEX] << SHIFT_ALPHA)
                | (chR[MEDIAN_MID_INDEX] << SHIFT_RED)
                | (chG[MEDIAN_MID_INDEX] << SHIFT_GREEN)
                | chB[MEDIAN_MID_INDEX];
    }

    private static final class MedianScratch {
        private final int[] a = new int[MEDIAN_WINDOW_LEN];
        private final int[] r = new int[MEDIAN_WINDOW_LEN];
        private final int[] g = new int[MEDIAN_WINDOW_LEN];
        private final int[] b = new int[MEDIAN_WINDOW_LEN];
    }
}
