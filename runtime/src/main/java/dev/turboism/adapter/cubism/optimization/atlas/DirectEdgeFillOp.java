package dev.turboism.adapter.cubism.optimization.atlas;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Row-major implementation of Cubism's {@code edgeFill} filter ({@code jp.noids.graphics.h.a}).
 *
 * <p>Replaces column-major vertical passes with sequential row-by-row column state sweeps in
 * L1/L2 cache and parallelizes large buffers across row chunks and column strips.</p>
 */
public final class DirectEdgeFillOp {

    private static final int ALPHA_THRESHOLD = 3;
    private static final int ALPHA_SHIFT = 24;
    private static final int BYTE_MASK = 0xFF;
    private static final int RGB_MASK = 0x00FF_FFFF;
    private static final int ALPHA_MASK = 0xFF00_0000;
    private static final int FALLBACK_GREY = 0x0080_8080;
    private static final int NO_COLOR = -1;
    private static final long PARALLEL_PIXEL_THRESHOLD = 65_536L;
    private static final int ROW_CHUNK_SIZE = 64;
    private static final int COL_STRIP_WIDTH = 512;

    private DirectEdgeFillOp() {}

    /**
     * Executes bit-identical horizontal and vertical edge fill in row-major memory order.
     *
     * @param image target {@code BufferedImage}
     * @param distance fill distance threshold
     * @return {@code true} when backed by {@code DataBufferInt}, or {@code false} to use native
     */
    public static boolean tryEdgeFill(final BufferedImage image, final int distance) {
        if (image == null) {
            return false;
        }
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt intBuffer)) {
            return false;
        }
        edgeFillRowMajor(intBuffer.getData(), image.getWidth(), image.getHeight(), distance);
        return true;
    }

    /** Executes horizontal and vertical edge fill directly over raw ARGB {@code int[]} pixels. */
    public static void edgeFillRowMajor(final int[] pixels, final int width, final int height, final int distance) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if ((long) width * height < PARALLEL_PIXEL_THRESHOLD) {
            sweepHorizontalRange(pixels, width, 0, height, distance);
            sweepVerticalStrip(pixels, width, height, 0, width, distance);
            return;
        }

        final int rowChunks = (height + ROW_CHUNK_SIZE - 1) / ROW_CHUNK_SIZE;
        IntStream.range(0, rowChunks).parallel().forEach(chunk -> {
            final int y0 = chunk * ROW_CHUNK_SIZE;
            final int y1 = Math.min(height, y0 + ROW_CHUNK_SIZE);
            sweepHorizontalRange(pixels, width, y0, y1, distance);
        });

        final int colStrips = (width + COL_STRIP_WIDTH - 1) / COL_STRIP_WIDTH;
        if (colStrips <= 1) {
            sweepVerticalStrip(pixels, width, height, 0, width, distance);
        } else {
            IntStream.range(0, colStrips).parallel().forEach(strip -> {
                final int x0 = strip * COL_STRIP_WIDTH;
                final int x1 = Math.min(width, x0 + COL_STRIP_WIDTH);
                sweepVerticalStrip(pixels, width, height, x0, x1, distance);
            });
        }
    }

    private static void sweepHorizontalRange(
            final int[] pixels, final int width, final int yStart, final int yEnd, final int distance) {
        for (int y = yStart, rowOff = yStart * width; y < yEnd; y++, rowOff += width) {
            int dist = 0;
            int rgb = NO_COLOR;
            for (int x = 0; x < width; x++) {
                final int idx = rowOff + x;
                final int px = pixels[idx];
                final int alpha = (px >>> ALPHA_SHIFT) & BYTE_MASK;
                if (alpha > ALPHA_THRESHOLD) {
                    dist = 0;
                    rgb = px & RGB_MASK;
                } else if (rgb != NO_COLOR && dist < distance) {
                    pixels[idx] = (px & ALPHA_MASK) | rgb;
                    if (alpha == 0) dist++;
                } else {
                    pixels[idx] = FALLBACK_GREY;
                }
            }

            dist = 0;
            rgb = NO_COLOR;
            for (int x = width - 1; x >= 0; x--) {
                final int idx = rowOff + x;
                final int px = pixels[idx];
                final int alpha = (px >>> ALPHA_SHIFT) & BYTE_MASK;
                if (alpha > ALPHA_THRESHOLD) {
                    dist = 0;
                    rgb = px & RGB_MASK;
                } else if (rgb != NO_COLOR && dist < distance) {
                    pixels[idx] = (px & ALPHA_MASK) | rgb;
                    if (alpha == 0) dist++;
                }
            }
        }
    }

    private static void sweepVerticalStrip(
            final int[] pixels,
            final int width,
            final int height,
            final int xStart,
            final int xEnd,
            final int distance) {
        final int stripWidth = xEnd - xStart;
        if (stripWidth <= 0) {
            return;
        }
        final int[] colRgb = new int[stripWidth];
        final int[] colDist = new int[stripWidth];
        Arrays.fill(colRgb, NO_COLOR);
        for (int y = 0, rowOff = xStart; y < height; y++, rowOff += width) {
            sweepVerticalRowStrip(pixels, rowOff, stripWidth, distance, colRgb, colDist);
        }

        Arrays.fill(colRgb, NO_COLOR);
        Arrays.fill(colDist, 0);
        for (int y = height - 1, rowOff = y * width + xStart; y >= 0; y--, rowOff -= width) {
            sweepVerticalRowStrip(pixels, rowOff, stripWidth, distance, colRgb, colDist);
        }
    }

    private static void sweepVerticalRowStrip(
            final int[] pixels,
            final int rowOff,
            final int stripWidth,
            final int distance,
            final int[] colRgb,
            final int[] colDist) {
        for (int x = 0; x < stripWidth; x++) {
            final int idx = rowOff + x;
            final int px = pixels[idx];
            final int alpha = (px >>> ALPHA_SHIFT) & BYTE_MASK;
            if (alpha > ALPHA_THRESHOLD) {
                colDist[x] = 0;
                colRgb[x] = px & RGB_MASK;
            } else {
                final int rgb = colRgb[x];
                final int dist = colDist[x];
                if (rgb != NO_COLOR && dist < distance) {
                    pixels[idx] = (px & ALPHA_MASK) | rgb;
                    if (alpha == 0) {
                        colDist[x] = dist + 1;
                    }
                }
            }
        }
    }
}
