package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.plugin.turbomesh.domain.features.MeshFeatureAnalyzer;
import java.util.ArrayDeque;

/**
 * Cropped texture raster with alpha silhouette, optional bleed domain, and perceptual colour planes.
 *
 * <p>Transparent border pixels inherit the colour of the nearest opaque pixel. Without that
 * substitution the black transparent area would dominate every colour comparison along the
 * silhouette, and segmentation would follow the alpha edge instead of the texture.</p>
 */
final class RegionRaster {

    /** Alpha value from which a pixel counts as solid material. */
    static final int ALPHA_SOLID = 128;

    /** Alpha value from which an anti-aliased edge pixel counts as visible artwork. */
    static final int ALPHA_VISIBLE = 16;

    /** Minimum transparent core radius preserved inside interior alpha holes during bleed expansion. */
    static final double MIN_HOLE_CORE_PX = 2.0;

    /** Alpha value from which a pixel counts as a colour source for the border fill. */
    static final int ALPHA_OPAQUE = 250;

    /** Crop margin around the silhouette, in pixels. */
    static final int CROP_MARGIN_PX = 4;

    /** Percentile of the 4-neighbour CIELAB steps that fixes the automatic contrast. */
    static final double AUTO_CONTRAST_PERCENTILE = 0.65;

    /** Gain that turns the background step into a region merge threshold. */
    static final double AUTO_CONTRAST_GAIN = 1.6;

    /** Lowest automatic contrast; keeps dark, low-contrast layers separated. */
    static final double AUTO_CONTRAST_FLOOR = 3.0;

    /** Highest automatic contrast; keeps a shaded layer from shredding its soft ramps. */
    static final double AUTO_CONTRAST_CEILING = 8.5;

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
    private final boolean[] domainMask;
    private final float[] rgb;
    private final float[] lab;

    private RegionRaster(
            final int width,
            final int height,
            final int originX,
            final int originY,
            final double silhouetteSpan,
            final boolean[] mask,
            final boolean[] domainMask,
            final float[] rgb,
            final float[] lab) {
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.silhouetteSpan = silhouetteSpan;
        this.mask = mask;
        this.domainMask = domainMask;
        this.rgb = rgb;
        this.lab = lab;
    }

    /**
     * Measures the longest bounding-box extent of solid pixels without cropping the raster.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @param width image width in pixels
     * @param height image height in pixels
     * @return longest solid bounding-box span in pixels
     * @throws IllegalArgumentException when the image holds no pixel above {@link #ALPHA_SOLID}
     */
    static double silhouetteSpan(final int[] argb, final int width, final int height) {
        final int[] bounds = solidBounds(argb, width, height);
        return Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]);
    }

    /**
     * Crops an ARGB image to the solid alpha area without bleed expansion.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @throws IllegalArgumentException when the image holds no pixel above {@link #ALPHA_SOLID}
     */
    static RegionRaster cropped(final int[] argb, final int width, final int height) {
        return cropped(argb, width, height, 0.0);
    }

    /**
     * Crops an ARGB image to the solid alpha area plus bleed and safety margin.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @param width image width in pixels
     * @param height image height in pixels
     * @param bleedMarginPx transparent bleed expansion outside visible artwork, in pixels
     * @return cropped raster holding painted and bleed-expanded masks
     * @throws IllegalArgumentException when the image holds no pixel above {@link #ALPHA_SOLID}
     */
    static RegionRaster cropped(final int[] argb, final int width, final int height, final double bleedMarginPx) {
        final int[] bounds = solidBounds(argb, width, height);
        final int minX = bounds[0];
        final int minY = bounds[1];
        final int maxX = bounds[2];
        final int maxY = bounds[3];
        final double effectiveBleed = Math.max(0.0, bleedMarginPx);
        final int pad = (int) Math.ceil(effectiveBleed) + CROP_MARGIN_PX;
        final int outsidePad = effectiveBleed > 0.0 ? pad : 0;
        final int originX = Math.max(-outsidePad, minX - pad);
        final int originY = Math.max(-outsidePad, minY - pad);
        final int endX = Math.min(width + outsidePad, maxX + pad + 1);
        final int endY = Math.min(height + outsidePad, maxY + pad + 1);
        final int cropWidth = endX - originX;
        final int cropHeight = endY - originY;
        final int[] croppedPixels = copyWithTransparentPadding(argb, width, height, originX, originY, endX, endY);
        final boolean[] solidMask = new boolean[cropWidth * cropHeight];
        final boolean[] visibleMask = new boolean[cropWidth * cropHeight];
        for (int i = 0; i < solidMask.length; i++) {
            final int a = alpha(croppedPixels[i]);
            solidMask[i] = a >= ALPHA_SOLID;
            visibleMask[i] = a >= ALPHA_VISIBLE;
        }
        final boolean[] domainMask = effectiveBleed <= 0.0
                ? solidMask.clone()
                : buildBleedDomain(visibleMask, cropWidth, cropHeight, effectiveBleed);
        final float[] rgb = filledColour(croppedPixels, cropWidth, cropHeight);
        final float[] lab = new float[rgb.length];
        for (int i = 0; i < solidMask.length; i++) {
            final float[] value = MeshFeatureAnalyzer.srgbToLab(rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2]);
            lab[3 * i] = value[0];
            lab[3 * i + 1] = value[1];
            lab[3 * i + 2] = value[2];
        }
        return new RegionRaster(
                cropWidth,
                cropHeight,
                originX,
                originY,
                Math.max(maxX - minX, maxY - minY),
                solidMask,
                domainMask,
                rgb,
                lab);
    }

    /** Supplies transparent pixels beyond the source edges for silhouette expansion. */
    private static int[] copyWithTransparentPadding(
            final int[] argb,
            final int width,
            final int height,
            final int startX,
            final int startY,
            final int endX,
            final int endY) {
        final int stride = endX - startX;
        final int[] pixels = new int[stride * (endY - startY)];
        final int copyX = Math.max(0, startX);
        final int copyWidth = Math.min(endX, width) - copyX;
        final int copyEndY = Math.min(endY, height);
        for (int y = Math.max(0, startY); y < copyEndY; y++) {
            System.arraycopy(argb, y * width + copyX, pixels, (y - startY) * stride + copyX - startX, copyWidth);
        }
        return pixels;
    }

    private static int[] solidBounds(final int[] argb, final int width, final int height) {
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
        return new int[] {minX, minY, maxX, maxY};
    }

    /**
     * Expands the visible silhouette into transparent bleed while keeping interior alpha holes open.
     *
     * <p>Exterior transparent pixels dilate up to {@code bleedMarginPx}. Interior holes cap their
     * inward bleed below their maximum inscribed radius to keep small holes open.</p>
     */
    private static boolean[] buildBleedDomain(
            final boolean[] visibleMask, final int cropWidth, final int cropHeight, final double bleedMarginPx) {
        final int cells = cropWidth * cropHeight;
        final double[] distance = EuclideanDistanceTransform.distanceFromMask(visibleMask, cropWidth, cropHeight);
        final boolean[] exterior = new boolean[cells];
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < cropWidth; x++) {
            seedExterior(visibleMask, exterior, queue, x, 0, cropWidth);
            seedExterior(visibleMask, exterior, queue, x, cropHeight - 1, cropWidth);
        }
        for (int y = 0; y < cropHeight; y++) {
            seedExterior(visibleMask, exterior, queue, 0, y, cropWidth);
            seedExterior(visibleMask, exterior, queue, cropWidth - 1, y, cropWidth);
        }
        floodExterior(visibleMask, exterior, queue, cropWidth, cropHeight);

        final int[] holeIds = new int[cells];
        int holeCount = 0;
        for (int start = 0; start < cells; start++) {
            if (visibleMask[start] || exterior[start] || holeIds[start] != 0) {
                continue;
            }
            holeCount++;
            holeIds[start] = holeCount;
            queue.add(start);
            while (!queue.isEmpty()) {
                final int cell = queue.poll();
                final int x = cell % cropWidth;
                final int y = cell / cropWidth;
                if (x > 0) visitHole(visibleMask, exterior, holeIds, queue, cell - 1, holeCount);
                if (x + 1 < cropWidth) visitHole(visibleMask, exterior, holeIds, queue, cell + 1, holeCount);
                if (y > 0) visitHole(visibleMask, exterior, holeIds, queue, cell - cropWidth, holeCount);
                if (y + 1 < cropHeight) visitHole(visibleMask, exterior, holeIds, queue, cell + cropWidth, holeCount);
            }
        }
        final double[] holePeak = new double[holeCount + 1];
        for (int i = 0; i < cells; i++) {
            final int id = holeIds[i];
            if (id > 0) {
                holePeak[id] = Math.max(holePeak[id], distance[i]);
            }
        }

        final boolean[] domain = visibleMask.clone();
        for (int cell = 0; cell < cells; cell++) {
            if (visibleMask[cell]) {
                continue;
            }
            if (exterior[cell]) {
                domain[cell] = distance[cell] <= bleedMarginPx;
            } else {
                final double holeBleed =
                        Math.min(bleedMarginPx, Math.max(0.0, holePeak[holeIds[cell]] - MIN_HOLE_CORE_PX));
                domain[cell] = distance[cell] <= holeBleed;
            }
        }
        return domain;
    }

    private static void seedExterior(
            final boolean[] visibleMask,
            final boolean[] exterior,
            final ArrayDeque<Integer> queue,
            final int x,
            final int y,
            final int width) {
        final int cell = y * width + x;
        if (!visibleMask[cell] && !exterior[cell]) {
            exterior[cell] = true;
            queue.add(cell);
        }
    }

    private static void floodExterior(
            final boolean[] visibleMask,
            final boolean[] exterior,
            final ArrayDeque<Integer> queue,
            final int width,
            final int height) {
        while (!queue.isEmpty()) {
            final int cell = queue.poll();
            final int x = cell % width;
            final int y = cell / width;
            if (x > 0) visitExterior(visibleMask, exterior, queue, cell - 1);
            if (x + 1 < width) visitExterior(visibleMask, exterior, queue, cell + 1);
            if (y > 0) visitExterior(visibleMask, exterior, queue, cell - width);
            if (y + 1 < height) visitExterior(visibleMask, exterior, queue, cell + width);
        }
    }

    private static void visitExterior(
            final boolean[] visibleMask, final boolean[] exterior, final ArrayDeque<Integer> queue, final int cell) {
        if (!visibleMask[cell] && !exterior[cell]) {
            exterior[cell] = true;
            queue.add(cell);
        }
    }

    private static void visitHole(
            final boolean[] visibleMask,
            final boolean[] exterior,
            final int[] holeIds,
            final ArrayDeque<Integer> queue,
            final int cell,
            final int id) {
        if (!visibleMask[cell] && !exterior[cell] && holeIds[cell] == 0) {
            holeIds[cell] = id;
            queue.add(cell);
        }
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

    /** Painted solid pixels above {@link #ALPHA_SOLID}. */
    boolean[] mask() {
        return mask;
    }

    /** Bleed-expanded domain mask including visible anti-aliased fringes and bleed margin. */
    boolean[] domainMask() {
        return domainMask;
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
     * Automatic contrast: the 65th percentile of the 4-neighbour CIELAB step, scaled.
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
