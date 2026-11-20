package dev.turboism.adapter.cubism.textureatlas;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Row-major implementation of {@code jp.noids.graphics.h.a(BufferedImage, int)} ({@code edgeFill}).
 *
 * <p>Replaces the native column-major vertical passes with sequential row-by-row column state
 * sweeps in L1/L2 cache while producing pixel-for-pixel identical ARGB output.</p>
 */
public final class TextureAtlasEdgeFillDelegate {

    private static final int ALPHA_THRESHOLD = 3;
    private static final int ALPHA_SHIFT = 24;
    private static final int BYTE_MASK = 0xFF;
    private static final int RGB_MASK = 0x00FF_FFFF;
    private static final int ALPHA_MASK = 0xFF00_0000;
    private static final int FALLBACK_GREY = 0x0080_8080;
    private static final int NO_COLOR = -1;

    private TextureAtlasEdgeFillDelegate() {}

    /**
     * Executes bit-identical horizontal and vertical edge fill in row-major memory order.
     *
     * @param image target {@code BufferedImage}
     * @param distance fill distance threshold
     * @return {@code true} when backed by {@code DataBufferInt}, or {@code false} to use native
     */
    public static boolean tryEdgeFill(final BufferedImage image, final int distance) {
        if (image == null) return false;
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt intBuffer)) {
            return false;
        }
        edgeFillRowMajor(intBuffer.getData(), image.getWidth(), image.getHeight(), distance);
        return true;
    }

    static void edgeFillRowMajor(final int[] pixels, final int width, final int height, final int distance) {
        for (int y = 0, rowOff = 0; y < height; y++, rowOff += width) {
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

        final int[] colRgb = new int[width];
        final int[] colDist = new int[width];
        Arrays.fill(colRgb, NO_COLOR);
        for (int y = 0, rowOff = 0; y < height; y++, rowOff += width) {
            sweepVerticalRow(pixels, rowOff, width, distance, colRgb, colDist);
        }

        Arrays.fill(colRgb, NO_COLOR);
        Arrays.fill(colDist, 0);
        for (int y = height - 1, rowOff = y * width; y >= 0; y--, rowOff -= width) {
            sweepVerticalRow(pixels, rowOff, width, distance, colRgb, colDist);
        }
    }

    private static void sweepVerticalRow(
            final int[] pixels,
            final int rowOff,
            final int width,
            final int distance,
            final int[] colRgb,
            final int[] colDist) {
        for (int x = 0; x < width; x++) {
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
