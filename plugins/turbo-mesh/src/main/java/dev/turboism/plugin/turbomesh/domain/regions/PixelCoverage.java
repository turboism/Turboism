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

    /**
     * Share of the covered pixels that more than one triangle claims.
     *
     * <p>A triangle claims a pixel only when it covers the strict majority of that pixel. A planar
     * triangulation therefore claims every pixel at most once, because two triangles can split a
     * pixel but only one of them holds more than half of it. Any positive value is real overlap
     * rather than a shared edge.</p>
     *
     * @param vertices plan vertices in cropped raster pixel space
     * @param triangleIndices flat index triples into {@code vertices}
     * @return summed overlap count divided by the covered pixel count, or {@code 0} when empty
     */
    static double overlapRatio(
            final List<Point2> vertices, final int[] triangleIndices, final int width, final int height) {
        final int[] claims = claimedPixels(vertices, triangleIndices, width, height);
        long covered = 0L;
        long overlapping = 0L;
        for (final int claim : claims) {
            if (claim > 0) {
                covered++;
            }
            if (claim > 1) {
                overlapping += claim - 1L;
            }
        }
        return covered == 0L ? 0.0 : (double) overlapping / covered;
    }

    /** Per-pixel triangle multiplicity, in cropped raster pixel space. */
    private static int[] claimedPixels(
            final List<Point2> vertices, final int[] triangleIndices, final int width, final int height) {
        final int[] claims = new int[width * height];
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
                    final int cell = row * width + column;
                    if (strictlyCoveredSamples(xs, ys, column, row) > COVERED_SAMPLE_COUNT) {
                        claims[cell]++;
                    }
                }
            }
        }
        return claims;
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
        final double first = PixelGeometry.cross(xs[0], ys[0], xs[1], ys[1], x, y);
        final double second = PixelGeometry.cross(xs[1], ys[1], xs[2], ys[2], x, y);
        final double third = PixelGeometry.cross(xs[2], ys[2], xs[0], ys[0], x, y);
        final boolean negative = first < 0.0 || second < 0.0 || third < 0.0;
        final boolean positive = first > 0.0 || second > 0.0 || third > 0.0;
        return !(negative && positive);
    }

    /**
     * Supersamples strictly inside one triangle, in cropped raster pixel space.
     *
     * <p>Overlap counting must not credit a sample on a shared edge to both neighbours: the
     * boundary belongs to neither triangle, so a clean tiling can never claim one sample twice.
     * Only a strict majority of a pixel can claim it, and two triangles cannot both hold a strict
     * majority unless they genuinely overlap.</p>
     */
    private static int strictlyCoveredSamples(final double[] xs, final double[] ys, final int column, final int row) {
        int hits = 0;
        for (int sampleY = 0; sampleY < SAMPLES_PER_AXIS; sampleY++) {
            for (int sampleX = 0; sampleX < SAMPLES_PER_AXIS; sampleX++) {
                final double x = column + (sampleX + 0.5) / SAMPLES_PER_AXIS;
                final double y = row + (sampleY + 0.5) / SAMPLES_PER_AXIS;
                final double first = PixelGeometry.cross(xs[0], ys[0], xs[1], ys[1], x, y);
                final double second = PixelGeometry.cross(xs[1], ys[1], xs[2], ys[2], x, y);
                final double third = PixelGeometry.cross(xs[2], ys[2], xs[0], ys[0], x, y);
                final boolean inside =
                        (first > 0.0 && second > 0.0 && third > 0.0) || (first < 0.0 && second < 0.0 && third < 0.0);
                if (inside) {
                    hits++;
                }
            }
        }
        return hits;
    }
}
