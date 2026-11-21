package dev.turboism.adapter.cubism.exportperf;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Replaces {@code com.live2d.util.d.a.b} with an unboxed packed-int distance propagation pass.
 *
 * <p>Operates directly on {@code DataBufferInt} pixels in {@code 0xAARRGGBB} layout instead of
 * allocating {@code int[w * h * 4]} and {@code WritableRaster.getPixels}, cutting memory traffic
 * by over 4x while guaranteeing pixel-identical edge color extension.</p>
 */
public final class EdgeExtendFastOp {

    private static final int TRANSPARENT_GREY_RGB = 0x00808080;
    private static final int MAX_DIST_SENTINEL = 0x00FFFFFF;
    private static final int ALPHA_OPAQUE = 255;
    private static final int MIN_DIMENSION = 2;
    private static final int INITIAL_SEED_ALPHA = 1;
    private static final int STEP_ALPHA_OFFSET = 2;
    private static final double STEP_GROWTH_BASE = 1.5d;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_ALPHA = 24;
    private static final int PERCENT_SCALE = 100;
    private static final int PARALLEL_PIXEL_THRESHOLD = 65_536;
    private static final int PARALLEL_CHUNK_SIZE = 64;

    private static final String MSG_READ_PIXELS = "read pixels.";
    private static final String MSG_FILL_TRANSPARENT = "fill transparent color.";
    private static final String MSG_BACKUP_ALPHA = "backup alpha.";
    private static final String MSG_RESTORE_ALPHA = "restore alpha.";
    private static final String MSG_WRITE_PIXELS = "write pixels.";
    private static final String MSG_COMPLETE = "complete.";
    private static final String MSG_SPREAD_PREFIX = "spread color dist:";

    private static volatile Method progressInvoke;

    private EdgeExtendFastOp() {}

    /**
     * Extends opaque edge RGB colors into transparent regions for {@code TYPE_INT_ARGB} images.
     *
     * @param image target buffered image modified in place
     * @param alphaThreshold minimum alpha threshold exclusive to act as a seed pixel
     * @param progress optional Kotlin {@code Function2<Integer, String, Unit>} callback
     * @return {@code true} if handled directly, {@code false} if fallback to host code is needed
     */
    public static boolean tryExtendEdgeColor(
            final BufferedImage image, final int alphaThreshold, final Object progress) {
        if (image == null) {
            return false;
        }
        if (image.getType() != BufferedImage.TYPE_INT_ARGB || image.getRaster().getNumBands() != 4) {
            return false;
        }
        if (alphaThreshold >= ALPHA_OPAQUE) {
            return true;
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            return true;
        }
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt intBuf) || intBuf.getNumBanks() != 1 || intBuf.getOffset() != 0) {
            return false;
        }
        final int[] pixels = intBuf.getData();
        final int pixelCount = width * height;
        if (pixels.length < pixelCount) {
            return false;
        }

        reportProgress(progress, 0, MSG_READ_PIXELS);
        reportProgress(progress, 0, MSG_FILL_TRANSPARENT);
        reportProgress(progress, 0, MSG_BACKUP_ALPHA);

        final byte[] origAlpha = new byte[pixelCount];
        final int[] dist = new int[pixelCount];
        prepareWorkingPixels(pixels, origAlpha, pixelCount, alphaThreshold);

        final int steps = (int) (Math.log(Math.max(width, height)) / Math.log(STEP_GROWTH_BASE));
        double stepFloat = 1.0d;
        for (int step = 0; step < steps; step++) {
            final int maxDist = (int) stepFloat;
            if (progress != null) {
                reportProgress(progress, (step * PERCENT_SCALE) / steps, MSG_SPREAD_PREFIX + maxDist);
            }
            final int writeAlpha = step + STEP_ALPHA_OFFSET;
            if (!runPropagationPass(pixels, dist, width, height, maxDist, writeAlpha)) {
                break;
            }
            stepFloat *= STEP_GROWTH_BASE;
        }

        reportProgress(progress, PERCENT_SCALE, MSG_RESTORE_ALPHA);
        restoreAlpha(pixels, origAlpha, pixelCount);
        reportProgress(progress, PERCENT_SCALE, MSG_WRITE_PIXELS);
        image.flush();
        reportProgress(progress, PERCENT_SCALE, MSG_COMPLETE);
        return true;
    }

    private static void prepareWorkingPixels(
            final int[] pixels, final byte[] origAlpha, final int pixelCount, final int alphaThreshold) {
        for (int i = 0; i < pixelCount; i++) {
            final int px = pixels[i];
            final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
            origAlpha[i] = (byte) a;
            final int rgb = a == 0 ? TRANSPARENT_GREY_RGB : (px & RGB_MASK);
            final int workA = a > alphaThreshold ? INITIAL_SEED_ALPHA : 0;
            pixels[i] = (workA << SHIFT_ALPHA) | rgb;
        }
    }

    private static void restoreAlpha(final int[] pixels, final byte[] origAlpha, final int pixelCount) {
        for (int i = 0; i < pixelCount; i++) {
            pixels[i] = ((origAlpha[i] & BYTE_MASK) << SHIFT_ALPHA) | (pixels[i] & RGB_MASK);
        }
    }

    private static boolean runPropagationPass(
            final int[] pixels,
            final int[] dist,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        Arrays.fill(dist, MAX_DIST_SENTINEL);
        final boolean rowChanged;
        final boolean colChanged;
        if ((long) width * height < PARALLEL_PIXEL_THRESHOLD) {
            rowChanged = sweepRowRange(0, height, pixels, dist, width, maxDist, writeAlpha);
            colChanged = sweepColRange(0, width, pixels, dist, width, height, maxDist, writeAlpha);
        } else {
            rowChanged = parallelSweepRows(pixels, dist, width, height, maxDist, writeAlpha);
            colChanged = parallelSweepCols(pixels, dist, width, height, maxDist, writeAlpha);
        }
        return rowChanged || colChanged;
    }

    private static boolean parallelSweepRows(
            final int[] pixels,
            final int[] dist,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        final int chunks = (height + PARALLEL_CHUNK_SIZE - 1) / PARALLEL_CHUNK_SIZE;
        return IntStream.range(0, chunks)
                        .parallel()
                        .map(chunk -> {
                            final int y0 = chunk * PARALLEL_CHUNK_SIZE;
                            final int y1 = Math.min(height, y0 + PARALLEL_CHUNK_SIZE);
                            return sweepRowRange(y0, y1, pixels, dist, width, maxDist, writeAlpha) ? 1 : 0;
                        })
                        .sum()
                > 0;
    }

    private static boolean parallelSweepCols(
            final int[] pixels,
            final int[] dist,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        final int chunks = (width + PARALLEL_CHUNK_SIZE - 1) / PARALLEL_CHUNK_SIZE;
        return IntStream.range(0, chunks)
                        .parallel()
                        .map(chunk -> {
                            final int x0 = chunk * PARALLEL_CHUNK_SIZE;
                            final int x1 = Math.min(width, x0 + PARALLEL_CHUNK_SIZE);
                            return sweepColRange(x0, x1, pixels, dist, width, height, maxDist, writeAlpha) ? 1 : 0;
                        })
                        .sum()
                > 0;
    }

    private static boolean sweepRowRange(
            final int yStart,
            final int yEnd,
            final int[] pixels,
            final int[] dist,
            final int width,
            final int maxDist,
            final int writeAlpha) {
        boolean changed = false;
        final int writeMask = writeAlpha << SHIFT_ALPHA;
        for (int y = yStart; y < yEnd; y++) {
            final int rowBase = y * width;
            int seedRgb = 0;
            int seedX = -1;
            boolean hasSeed = false;
            for (int x = 0; x < width; x++) {
                final int idx = rowBase + x;
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb = px & RGB_MASK;
                    seedX = x;
                    hasSeed = true;
                } else if (hasSeed) {
                    final int d = x - seedX;
                    if (d <= maxDist && dist[idx] > d) {
                        dist[idx] = d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    }
                }
            }

            hasSeed = false;
            for (int x = width - 1; x >= 0; x--) {
                final int idx = rowBase + x;
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb = px & RGB_MASK;
                    seedX = x;
                    hasSeed = true;
                } else if (hasSeed) {
                    final int d = seedX - x;
                    if (d <= maxDist && dist[idx] > d) {
                        dist[idx] = d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean sweepColRange(
            final int xStart,
            final int xEnd,
            final int[] pixels,
            final int[] dist,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        boolean changed = false;
        final int writeMask = writeAlpha << SHIFT_ALPHA;
        for (int x = xStart; x < xEnd; x++) {
            int seedRgb = 0;
            int seedY = -1;
            boolean hasSeed = false;
            int idx = x;
            for (int y = 0; y < height; y++, idx += width) {
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb = px & RGB_MASK;
                    seedY = y;
                    hasSeed = true;
                } else if (hasSeed) {
                    final int d = y - seedY;
                    if (d <= maxDist && dist[idx] > d) {
                        dist[idx] = d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    }
                }
            }

            hasSeed = false;
            idx = (height - 1) * width + x;
            for (int y = height - 1; y >= 0; y--, idx -= width) {
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb = px & RGB_MASK;
                    seedY = y;
                    hasSeed = true;
                } else if (hasSeed) {
                    final int d = seedY - y;
                    if (d <= maxDist && dist[idx] > d) {
                        dist[idx] = d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static void reportProgress(final Object progress, final int percent, final String stage) {
        if (progress == null) {
            return;
        }
        try {
            Method invoke = progressInvoke;
            if (invoke == null) {
                invoke = progress.getClass().getMethod("invoke", Object.class, Object.class);
                progressInvoke = invoke;
            }
            invoke.invoke(progress, Integer.valueOf(percent), stage);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Best-effort progress reporting only.
        }
    }
}
