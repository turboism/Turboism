package dev.turboism.adapter.cubism.optimization.export;

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
    private static final short SHORT_MAX_DIST = Short.MAX_VALUE;
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
    private static final int COL_STRIP_WIDTH = 512;

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
        if (Math.max(width, height) >= SHORT_MAX_DIST) {
            return false;
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
        final short[] dist = new short[pixelCount];
        final boolean[] rowIncomplete = new boolean[height];
        final int strips = (width + COL_STRIP_WIDTH - 1) / COL_STRIP_WIDTH;
        final boolean[] colStripIncomplete = new boolean[strips];
        Arrays.fill(colStripIncomplete, true);
        prepareWorkingPixels(pixels, origAlpha, rowIncomplete, width, height, alphaThreshold);

        final int steps = (int) (Math.log(Math.max(width, height)) / Math.log(STEP_GROWTH_BASE));
        double stepFloat = 1.0d;
        for (int step = 0; step < steps; step++) {
            final int maxDist = (int) stepFloat;
            if (progress != null) {
                reportProgress(progress, (step * PERCENT_SCALE) / steps, MSG_SPREAD_PREFIX + maxDist);
            }
            final int writeAlpha = step + STEP_ALPHA_OFFSET;
            if (!runPropagationPass(
                    pixels, dist, rowIncomplete, colStripIncomplete, width, height, maxDist, writeAlpha)) {
                break;
            }
            stepFloat *= STEP_GROWTH_BASE;
        }

        reportProgress(progress, PERCENT_SCALE, MSG_RESTORE_ALPHA);
        restoreAlpha(pixels, origAlpha, width, height);
        reportProgress(progress, PERCENT_SCALE, MSG_WRITE_PIXELS);
        image.flush();
        reportProgress(progress, PERCENT_SCALE, MSG_COMPLETE);
        return true;
    }

    private static void prepareWorkingPixels(
            final int[] pixels,
            final byte[] origAlpha,
            final boolean[] rowIncomplete,
            final int width,
            final int height,
            final int alphaThreshold) {
        if ((long) width * height < PARALLEL_PIXEL_THRESHOLD) {
            prepareWorkingRowRange(pixels, origAlpha, rowIncomplete, width, 0, height, alphaThreshold);
            return;
        }
        final int chunks = (height + PARALLEL_CHUNK_SIZE - 1) / PARALLEL_CHUNK_SIZE;
        IntStream.range(0, chunks).parallel().forEach(chunk -> {
            final int y0 = chunk * PARALLEL_CHUNK_SIZE;
            final int y1 = Math.min(height, y0 + PARALLEL_CHUNK_SIZE);
            prepareWorkingRowRange(pixels, origAlpha, rowIncomplete, width, y0, y1, alphaThreshold);
        });
    }

    private static void prepareWorkingRowRange(
            final int[] pixels,
            final byte[] origAlpha,
            final boolean[] rowIncomplete,
            final int width,
            final int yStart,
            final int yEnd,
            final int alphaThreshold) {
        for (int y = yStart; y < yEnd; y++) {
            final int rowBase = y * width;
            boolean hasZero = false;
            for (int x = 0; x < width; x++) {
                final int i = rowBase + x;
                final int px = pixels[i];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                origAlpha[i] = (byte) a;
                final int rgb = a == 0 ? TRANSPARENT_GREY_RGB : (px & RGB_MASK);
                final int workA;
                if (a > alphaThreshold) {
                    workA = INITIAL_SEED_ALPHA;
                } else {
                    workA = 0;
                    hasZero = true;
                }
                pixels[i] = (workA << SHIFT_ALPHA) | rgb;
            }
            rowIncomplete[y] = hasZero;
        }
    }

    private static void restoreAlpha(final int[] pixels, final byte[] origAlpha, final int width, final int height) {
        final int pixelCount = width * height;
        if (pixelCount < PARALLEL_PIXEL_THRESHOLD) {
            for (int i = 0; i < pixelCount; i++) {
                pixels[i] = ((origAlpha[i] & BYTE_MASK) << SHIFT_ALPHA) | (pixels[i] & RGB_MASK);
            }
            return;
        }
        final int chunks = (height + PARALLEL_CHUNK_SIZE - 1) / PARALLEL_CHUNK_SIZE;
        IntStream.range(0, chunks).parallel().forEach(chunk -> {
            final int start = chunk * PARALLEL_CHUNK_SIZE * width;
            final int end = Math.min(pixelCount, start + PARALLEL_CHUNK_SIZE * width);
            for (int i = start; i < end; i++) {
                pixels[i] = ((origAlpha[i] & BYTE_MASK) << SHIFT_ALPHA) | (pixels[i] & RGB_MASK);
            }
        });
    }

    private static boolean runPropagationPass(
            final int[] pixels,
            final short[] dist,
            final boolean[] rowIncomplete,
            final boolean[] colStripIncomplete,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        final boolean rowChanged;
        final boolean colChanged;
        if ((long) width * height < PARALLEL_PIXEL_THRESHOLD) {
            rowChanged = sweepRowRange(0, height, pixels, dist, rowIncomplete, width, maxDist, writeAlpha);
            colChanged =
                    sweepColRange(0, width, 0, pixels, dist, colStripIncomplete, width, height, maxDist, writeAlpha);
        } else {
            rowChanged = parallelSweepRows(pixels, dist, rowIncomplete, width, height, maxDist, writeAlpha);
            colChanged = parallelSweepCols(pixels, dist, colStripIncomplete, width, height, maxDist, writeAlpha);
        }
        return rowChanged || colChanged;
    }

    private static boolean parallelSweepRows(
            final int[] pixels,
            final short[] dist,
            final boolean[] rowIncomplete,
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
                            return sweepRowRange(y0, y1, pixels, dist, rowIncomplete, width, maxDist, writeAlpha)
                                    ? 1
                                    : 0;
                        })
                        .sum()
                > 0;
    }

    private static boolean parallelSweepCols(
            final int[] pixels,
            final short[] dist,
            final boolean[] colStripIncomplete,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        final int strips = colStripIncomplete.length;
        if (strips <= 1) {
            return sweepColRange(0, width, 0, pixels, dist, colStripIncomplete, width, height, maxDist, writeAlpha);
        }
        return IntStream.range(0, strips)
                        .parallel()
                        .map(strip -> {
                            final int x0 = strip * COL_STRIP_WIDTH;
                            final int x1 = Math.min(width, x0 + COL_STRIP_WIDTH);
                            return sweepColRange(
                                            x0,
                                            x1,
                                            strip,
                                            pixels,
                                            dist,
                                            colStripIncomplete,
                                            width,
                                            height,
                                            maxDist,
                                            writeAlpha)
                                    ? 1
                                    : 0;
                        })
                        .sum()
                > 0;
    }

    private static boolean sweepRowRange(
            final int yStart,
            final int yEnd,
            final int[] pixels,
            final short[] dist,
            final boolean[] rowIncomplete,
            final int width,
            final int maxDist,
            final int writeAlpha) {
        boolean changed = false;
        final int writeMask = writeAlpha << SHIFT_ALPHA;
        for (int y = yStart; y < yEnd; y++) {
            if (!rowIncomplete[y]) {
                continue;
            }
            final int rowBase = y * width;
            Arrays.fill(dist, rowBase, rowBase + width, SHORT_MAX_DIST);
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
                        dist[idx] = (short) d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    }
                }
            }
            if (!hasSeed) {
                continue;
            }

            hasSeed = false;
            boolean stillHasZero = false;
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
                        dist[idx] = (short) d;
                        pixels[idx] = writeMask | seedRgb;
                        changed = true;
                    } else if (a == 0) {
                        stillHasZero = true;
                    }
                } else if (a == 0) {
                    stillHasZero = true;
                }
            }
            if (!stillHasZero) {
                rowIncomplete[y] = false;
            }
        }
        return changed;
    }

    private static boolean sweepColRange(
            final int xStart,
            final int xEnd,
            final int stripIndex,
            final int[] pixels,
            final short[] dist,
            final boolean[] colStripIncomplete,
            final int width,
            final int height,
            final int maxDist,
            final int writeAlpha) {
        if (!colStripIncomplete[stripIndex]) {
            return false;
        }
        final int stripWidth = xEnd - xStart;
        if (stripWidth <= 0) {
            return false;
        }
        boolean changed = false;
        final int writeMask = writeAlpha << SHIFT_ALPHA;
        final int[] seedRgb = new int[stripWidth];
        final int[] seedY = new int[stripWidth];
        Arrays.fill(seedY, -1);

        // Sweep rows sequentially inside each column strip to avoid power-of-2 L1 cache-set thrashing.
        for (int y = 0; y < height; y++) {
            final int rowBase = y * width + xStart;
            for (int i = 0; i < stripWidth; i++) {
                final int idx = rowBase + i;
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb[i] = px & RGB_MASK;
                    seedY[i] = y;
                } else {
                    final int sy = seedY[i];
                    if (sy >= 0) {
                        final int d = y - sy;
                        if (d <= maxDist && dist[idx] > d) {
                            dist[idx] = (short) d;
                            pixels[idx] = writeMask | seedRgb[i];
                            changed = true;
                        }
                    }
                }
            }
        }

        Arrays.fill(seedY, -1);
        boolean stillHasZero = false;
        for (int y = height - 1; y >= 0; y--) {
            final int rowBase = y * width + xStart;
            for (int i = 0; i < stripWidth; i++) {
                final int idx = rowBase + i;
                final int px = pixels[idx];
                final int a = (px >>> SHIFT_ALPHA) & BYTE_MASK;
                if (a > 0 && a < writeAlpha) {
                    seedRgb[i] = px & RGB_MASK;
                    seedY[i] = y;
                } else {
                    final int sy = seedY[i];
                    if (sy >= 0) {
                        final int d = sy - y;
                        if (d <= maxDist && dist[idx] > d) {
                            dist[idx] = (short) d;
                            pixels[idx] = writeMask | seedRgb[i];
                            changed = true;
                        } else if (a == 0) {
                            stillHasZero = true;
                        }
                    } else if (a == 0) {
                        stillHasZero = true;
                    }
                }
            }
        }
        if (!stillHasZero) {
            colStripIncomplete[stripIndex] = false;
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
