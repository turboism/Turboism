package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Pixel coverage of the triangles that the plan actually returns.
 *
 * <p>An earlier version scored the outline polygon. That number stayed healthy while the returned
 * triangles covered well under half of the material, so it could not fail on the defect it was
 * meant to catch. The score therefore belongs to the triangle mesh, compared with the alpha mask
 * of the source texture.</p>
 */
final class PixelCoverage {

    /** Samples per pixel axis; a pixel counts as covered at half of the full sample count. */
    static final int SAMPLES_PER_AXIS = 4;

    private static final int SAMPLE_COUNT = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;

    private static final int COVERED_SAMPLE_COUNT = SAMPLE_COUNT / 2;

    private PixelCoverage() {}

    /**
     * Intersection over union of the solid alpha area and the area the triangles cover.
     *
     * @param vertices plan vertices in cropped raster pixel space
     * @param triangleIndices flat index triples into {@code vertices}
     * @return IoU in {@code [0, 1]}, or {@code 0} when the mask is empty
     */
    static double triangleIou(
            final List<Point2> vertices,
            final int[] triangleIndices,
            final boolean[] mask,
            final int width,
            final int height) {
        final boolean[] covered = coveredPixels(vertices, triangleIndices, width, height);
        long intersection = 0L;
        long union = 0L;
        for (int i = 0; i < covered.length; i++) {
            if (covered[i] && mask[i]) {
                intersection++;
            }
            if (covered[i] || mask[i]) {
                union++;
            }
        }
        return union == 0L ? 0.0 : (double) intersection / union;
    }

    /** Per-pixel membership of the triangle mesh, in cropped raster pixel space. */
    static boolean[] coveredPixels(
            final List<Point2> vertices, final int[] triangleIndices, final int width, final int height) {
        final int[] coverage = rasterise(vertices, triangleIndices, width, height);
        final boolean[] covered = new boolean[coverage.length];
        for (int i = 0; i < coverage.length; i++) {
            covered[i] = coverage[i] >= COVERED_SAMPLE_COUNT;
        }
        return covered;
    }

    /** Per-pixel supersample count of the triangle mesh, in cropped raster pixel space. */
    private static int[] rasterise(
            final List<Point2> vertices, final int[] triangleIndices, final int width, final int height) {
        final int[] coverage = new int[width * height];
        final double[] xs = new double[3];
        final double[] ys = new double[3];
        for (int t = 0; t + 2 < triangleIndices.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                final Point2 vertex = vertices.get(triangleIndices[t + k]);
                xs[k] = vertex.x();
                ys[k] = vertex.y();
            }
            final int minColumn = Math.max(0, (int) Math.floor(Math.min(xs[0], Math.min(xs[1], xs[2]))));
            final int maxColumn = Math.min(width - 1, (int) Math.ceil(Math.max(xs[0], Math.max(xs[1], xs[2]))));
            final int minRow = Math.max(0, (int) Math.floor(Math.min(ys[0], Math.min(ys[1], ys[2]))));
            final int maxRow = Math.min(height - 1, (int) Math.ceil(Math.max(ys[0], Math.max(ys[1], ys[2]))));
            for (int row = minRow; row <= maxRow; row++) {
                for (int column = minColumn; column <= maxColumn; column++) {
                    coverage[row * width + column] += coveredSamples(xs, ys, column, row);
                }
            }
        }
        return coverage;
    }

    private static int coveredSamples(final double[] xs, final double[] ys, final int column, final int row) {
        int hits = 0;
        for (int sampleY = 0; sampleY < SAMPLES_PER_AXIS; sampleY++) {
            for (int sampleX = 0; sampleX < SAMPLES_PER_AXIS; sampleX++) {
                final double x = column + (sampleX + 0.5) / SAMPLES_PER_AXIS;
                final double y = row + (sampleY + 0.5) / SAMPLES_PER_AXIS;
                if (contains(xs, ys, x, y)) {
                    hits++;
                }
            }
        }
        return hits;
    }

    /** Barycentric sign test; it accepts either winding and counts the boundary as inside. */
    private static boolean contains(final double[] xs, final double[] ys, final double x, final double y) {
        final double first = cross(xs[0], ys[0], xs[1], ys[1], x, y);
        final double second = cross(xs[1], ys[1], xs[2], ys[2], x, y);
        final double third = cross(xs[2], ys[2], xs[0], ys[0], x, y);
        final boolean negative = first < 0.0 || second < 0.0 || third < 0.0;
        final boolean positive = first > 0.0 || second > 0.0 || third > 0.0;
        return !(negative && positive);
    }

    private static double cross(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }
}
