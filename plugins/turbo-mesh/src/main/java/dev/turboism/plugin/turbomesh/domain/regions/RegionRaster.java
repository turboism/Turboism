package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.plugin.turbomesh.domain.features.MeshFeatureAnalyzer;

/**
 * Cropped texture raster with the alpha silhouette and perceptual colour planes.
 *
 * <p>Transparent border pixels inherit the colour of the nearest opaque pixel. Without that
 * substitution the black transparent area would dominate every colour comparison along the
 * silhouette, and segmentation would follow the alpha edge instead of the texture.</p>
 */
final class RegionRaster {

    /** Alpha value from which a pixel counts as solid material. */
    static final int ALPHA_SOLID = 128;

    /** Alpha value from which a pixel counts as a colour source for the border fill. */
    static final int ALPHA_OPAQUE = 250;

    /** Crop margin around the silhouette, in pixels. */
    static final int CROP_MARGIN_PX = 4;

    /** Percentile of the 4-neighbour CIELAB steps that fixes the automatic contrast. */
    static final double AUTO_CONTRAST_PERCENTILE = 0.75;

    /** Gain that turns the background step into a region merge threshold. */
    static final double AUTO_CONTRAST_GAIN = 2.0;

    /** Lowest automatic contrast; it keeps dark, low-contrast layers separated. */
    static final double AUTO_CONTRAST_FLOOR = 3.5;

    /** Highest automatic contrast; it keeps a shaded layer from shredding its soft ramps. */
    static final double AUTO_CONTRAST_CEILING = 12.0;

    /** Bin width of the CIELAB step histogram, in CIELAB units. */
    private static final double AUTO_CONTRAST_BIN = 0.25;

    /** Bin count that covers the automatic contrast range. */
    private static final int AUTO_CONTRAST_BINS = (int) Math.ceil(AUTO_CONTRAST_CEILING / AUTO_CONTRAST_BIN) + 1;

    private static final double CHAMFER_STRAIGHT = 1.0;
    private static final double CHAMFER_DIAGONAL = Math.sqrt(2.0);

    private final int width;
    private final int height;
    private final int originX;
    private final int originY;
    private final double silhouetteSpan;
    private final boolean[] mask;
    private final float[] rgb;
    private final float[] lab;

    private RegionRaster(
            final int width,
            final int height,
            final int originX,
            final int originY,
            final double silhouetteSpan,
            final boolean[] mask,
            final float[] rgb,
            final float[] lab) {
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.silhouetteSpan = silhouetteSpan;
        this.mask = mask;
        this.rgb = rgb;
        this.lab = lab;
    }

    /**
     * Crops an ARGB image to the solid alpha area plus a small margin.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @throws IllegalArgumentException when the image holds no pixel above {@link #ALPHA_SOLID}
     */
    static RegionRaster cropped(final int[] argb, final int width, final int height) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException(
                    "argb must hold " + (width * height) + " pixels but held " + argb.length);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (alpha(argb[y * width + x]) >= ALPHA_SOLID) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX) {
            throw new IllegalArgumentException("image has no pixel with alpha >= " + ALPHA_SOLID);
        }
        final int originX = Math.max(0, minX - CROP_MARGIN_PX);
        final int originY = Math.max(0, minY - CROP_MARGIN_PX);
        final int cropWidth = Math.min(width - 1, maxX + CROP_MARGIN_PX) - originX + 1;
        final int cropHeight = Math.min(height - 1, maxY + CROP_MARGIN_PX) - originY + 1;
        final int[] croppedPixels = new int[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            System.arraycopy(argb, (originY + y) * width + originX, croppedPixels, y * cropWidth, cropWidth);
        }
        final boolean[] mask = new boolean[cropWidth * cropHeight];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = alpha(croppedPixels[i]) >= ALPHA_SOLID;
        }
        final float[] rgb = filledColour(croppedPixels, cropWidth, cropHeight);
        final float[] lab = new float[rgb.length];
        for (int i = 0; i < mask.length; i++) {
            final float[] value = MeshFeatureAnalyzer.srgbToLab(rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2]);
            lab[3 * i] = value[0];
            lab[3 * i + 1] = value[1];
            lab[3 * i + 2] = value[2];
        }
        return new RegionRaster(
                cropWidth, cropHeight, originX, originY, Math.max(maxX - minX, maxY - minY), mask, rgb, lab);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int originX() {
        return originX;
    }

    int originY() {
        return originY;
    }

    /** Longest bounding-box extent of the solid area in source pixels. */
    double silhouetteSpan() {
        return silhouetteSpan;
    }

    boolean[] mask() {
        return mask;
    }

    float[] rgb() {
        return rgb;
    }

    float[] lab() {
        return lab;
    }

    int solidCount() {
        int count = 0;
        for (final boolean solid : mask) {
            if (solid) {
                count++;
            }
        }
        return count;
    }

    /**
     * Automatic contrast: the 75th percentile of the 4-neighbour CIELAB step, doubled.
     *
     * <p>A fixed threshold cannot serve a dark, low-contrast layer and a shaded one at the same
     * time. The percentile of the local step follows the artwork: a soft airbrush ramp stays near
     * the floor and merges, while a shaded layer lifts the threshold enough to keep its salient
     * creases and colour blocks apart.</p>
     *
     * @return the merge threshold clamped into {@code [AUTO_CONTRAST_FLOOR, AUTO_CONTRAST_CEILING]}
     */
    double autoContrast() {
        final int[] histogram = new int[AUTO_CONTRAST_BINS];
        final int samples = histogramLocalSteps(histogram);
        if (samples == 0) {
            return AUTO_CONTRAST_FLOOR;
        }
        final int target = (int) Math.ceil(samples * AUTO_CONTRAST_PERCENTILE);
        int seen = 0;
        int bin = 0;
        while (bin < histogram.length - 1 && seen + histogram[bin] < target) {
            seen += histogram[bin];
            bin++;
        }
        final double step = (bin + 0.5) * AUTO_CONTRAST_BIN;
        return Math.min(AUTO_CONTRAST_CEILING, Math.max(AUTO_CONTRAST_FLOOR, AUTO_CONTRAST_GAIN * step));
    }

    /**
     * Fills the step histogram and returns the sample count.
     *
     * <p>Only two solid pixels form a sample. A transparent neighbour carries the borrowed colour
     * of the nearest opaque pixel, so its step says nothing about the artwork.</p>
     */
    private int histogramLocalSteps(final int[] histogram) {
        int samples = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int cell = y * width + x;
                if (!mask[cell]) {
                    continue;
                }
                final double step = Math.max(
                        Math.max(stepTo(cell, x - 1, y), stepTo(cell, x + 1, y)),
                        Math.max(stepTo(cell, x, y - 1), stepTo(cell, x, y + 1)));
                histogram[Math.min(histogram.length - 1, (int) (step / AUTO_CONTRAST_BIN))]++;
                samples++;
            }
        }
        return samples;
    }

    /** CIELAB distance from one cell to a solid 4-neighbour; zero outside the raster or the material. */
    private double stepTo(final int cell, final int column, final int row) {
        if (column < 0 || row < 0 || column >= width || row >= height) {
            return 0.0;
        }
        final int neighbour = row * width + column;
        if (!mask[neighbour]) {
            return 0.0;
        }
        final double deltaL = lab[3 * cell] - lab[3 * neighbour];
        final double deltaA = lab[3 * cell + 1] - lab[3 * neighbour + 1];
        final double deltaB = lab[3 * cell + 2] - lab[3 * neighbour + 2];
        return Math.sqrt(deltaL * deltaL + deltaA * deltaA + deltaB * deltaB);
    }

    private static float[] filledColour(final int[] pixels, final int width, final int height) {
        final double[] distance = new double[pixels.length];
        final int[] nearest = new int[pixels.length];
        boolean hasOpaque = false;
        for (int i = 0; i < pixels.length; i++) {
            if (alpha(pixels[i]) >= ALPHA_OPAQUE) {
                distance[i] = 0.0;
                nearest[i] = i;
                hasOpaque = true;
            } else {
                distance[i] = Double.POSITIVE_INFINITY;
                nearest[i] = -1;
            }
        }
        if (hasOpaque) {
            propagateNearest(distance, nearest, width, height);
        } else {
            for (int i = 0; i < pixels.length; i++) {
                nearest[i] = i;
            }
        }
        final float[] rgb = new float[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            final int source = pixels[nearest[i] >= 0 ? nearest[i] : i];
            rgb[3 * i] = ((source >>> 16) & 0xFF) / 255f;
            rgb[3 * i + 1] = ((source >>> 8) & 0xFF) / 255f;
            rgb[3 * i + 2] = (source & 0xFF) / 255f;
        }
        return rgb;
    }

    private static void propagateNearest(
            final double[] distance, final int[] nearest, final int width, final int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                relax(distance, nearest, width, height, x, y, x - 1, y - 1, CHAMFER_DIAGONAL);
                relax(distance, nearest, width, height, x, y, x, y - 1, CHAMFER_STRAIGHT);
                relax(distance, nearest, width, height, x, y, x + 1, y - 1, CHAMFER_DIAGONAL);
                relax(distance, nearest, width, height, x, y, x - 1, y, CHAMFER_STRAIGHT);
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                relax(distance, nearest, width, height, x, y, x + 1, y, CHAMFER_STRAIGHT);
                relax(distance, nearest, width, height, x, y, x + 1, y + 1, CHAMFER_DIAGONAL);
                relax(distance, nearest, width, height, x, y, x, y + 1, CHAMFER_STRAIGHT);
                relax(distance, nearest, width, height, x, y, x - 1, y + 1, CHAMFER_DIAGONAL);
            }
        }
    }

    private static void relax(
            final double[] distance,
            final int[] nearest,
            final int width,
            final int height,
            final int x,
            final int y,
            final int sourceX,
            final int sourceY,
            final double step) {
        if (sourceX < 0 || sourceY < 0 || sourceX >= width || sourceY >= height) {
            return;
        }
        final int target = y * width + x;
        final int source = sourceY * width + sourceX;
        if (nearest[source] < 0) {
            return;
        }
        final double candidate = distance[source] + step;
        if (candidate < distance[target]) {
            distance[target] = candidate;
            nearest[target] = nearest[source];
        }
    }

    private static int alpha(final int argb) {
        return (argb >>> 24) & 0xFF;
    }
}
