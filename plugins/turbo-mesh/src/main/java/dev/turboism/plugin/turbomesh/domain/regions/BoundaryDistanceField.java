package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.Arrays;

/**
 * Euclidean distance from every raster cell to the nearest hard constraint, plus the length of that
 * constraint segment.
 *
 * <p>Interior sampling asks two questions per pixel: how deep the material reaches from the nearest
 * wall, and how dense the wall was sampled there. The answer comes from the
 * Felzenszwalb-Huttenlocher parabola envelope, which is exact for squared distances and therefore
 * beats any chamfer approximation. The same transform carries the segment length of the winning
 * seed, so a dense bend stays distinguishable from a long straight edge.</p>
 */
final class BoundaryDistanceField {

    /** Band around a segment that receives its exact distance, in pixels. */
    static final double SEED_BAND_PX = 1.5;

    private final double[] distance;
    private final double[] spacing;
    private final int width;
    private final int height;
    private final double peak;

    private BoundaryDistanceField(
            final double[] distance, final double[] spacing, final int width, final int height, final double peak) {
        this.distance = distance;
        this.spacing = spacing;
        this.width = width;
        this.height = height;
        this.peak = peak;
    }

    /**
     * Builds both fields from the hard constraints of the plane graph.
     *
     * @param graph sealed plane graph; direction guides are not walls and are skipped
     */
    static BoundaryDistanceField of(final SiteGraph.Snapshot graph, final int width, final int height) {
        final int cells = width * height;
        final double unset = 4.0 * (width * (double) width + height * (double) height + 1.0);
        final double[] seed = new double[cells];
        Arrays.fill(seed, unset);
        final double[] seedSpacing = new double[cells];
        for (int edge = 0; edge < graph.edgeCount(); edge++) {
            if (graph.edgeRoles()[edge] == ConstraintRole.DIRECTION_LINE) {
                continue;
            }
            final int a = graph.edgeA()[edge];
            final int b = graph.edgeB()[edge];
            seedBand(seed, seedSpacing, width, height, graph.x(a), graph.y(a), graph.x(b), graph.y(b));
        }
        final double[] squared = new double[cells];
        final double[] spacing = new double[cells];
        final int[] nearestRow = new int[cells];
        final int longest = Math.max(width, height);
        final double[] column = new double[longest];
        final double[] transformed = new double[longest];
        final int[] argmin = new int[longest];
        final int[] envelopeIndex = new int[longest + 1];
        final double[] envelopeLimit = new double[longest + 2];
        columnPass(seed, squared, nearestRow, width, height, column, transformed, argmin, envelopeIndex, envelopeLimit);
        rowPass(
                squared,
                nearestRow,
                seedSpacing,
                spacing,
                width,
                height,
                column,
                transformed,
                argmin,
                envelopeIndex,
                envelopeLimit);
        double peak = 0.0;
        for (int cell = 0; cell < cells; cell++) {
            final double value = Math.sqrt(squared[cell]);
            squared[cell] = value;
            peak = Math.max(peak, value);
        }
        return new BoundaryDistanceField(squared, spacing, width, height, peak);
    }

    double distance(final int x, final int y) {
        return distance[y * width + x];
    }

    double spacing(final int x, final int y) {
        return spacing[y * width + x];
    }

    int height() {
        return height;
    }

    /** Largest distance that any cell reaches, in pixels. */
    double peak() {
        return peak;
    }

    /** Writes the exact distance of every cell within the seed band of one segment. */
    private static void seedBand(
            final double[] seed,
            final double[] seedSpacing,
            final int width,
            final int height,
            final double ax,
            final double ay,
            final double bx,
            final double by) {
        final double length = Math.hypot(bx - ax, by - ay);
        if (length <= 0.0) {
            return;
        }
        final int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - SEED_BAND_PX));
        final int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(ax, bx) + SEED_BAND_PX));
        final int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - SEED_BAND_PX));
        final int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(ay, by) + SEED_BAND_PX));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                final double value = segmentDistance(ax, ay, bx, by, x, y);
                if (value > SEED_BAND_PX) {
                    continue;
                }
                final int cell = y * width + x;
                final double squared = value * value;
                if (squared < seed[cell]) {
                    seed[cell] = squared;
                    seedSpacing[cell] = length;
                }
            }
        }
    }

    private static double segmentDistance(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double lengthSquared = dx * dx + dy * dy;
        final double unclamped = lengthSquared <= 0.0 ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        final double t = Math.max(0.0, Math.min(1.0, unclamped));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** First parabola envelope pass, which solves every column of the raster. */
    private static void columnPass(
            final double[] seed,
            final double[] squared,
            final int[] nearestRow,
            final int width,
            final int height,
            final double[] column,
            final double[] transformed,
            final int[] argmin,
            final int[] envelopeIndex,
            final double[] envelopeLimit) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                column[y] = seed[y * width + x];
            }
            transform1D(column, height, transformed, argmin, envelopeIndex, envelopeLimit);
            for (int y = 0; y < height; y++) {
                squared[y * width + x] = transformed[y];
                nearestRow[y * width + x] = argmin[y];
            }
        }
    }

    /** Second envelope pass, which also hands every cell the segment length of its nearest seed. */
    private static void rowPass(
            final double[] squared,
            final int[] nearestRow,
            final double[] seedSpacing,
            final double[] spacing,
            final int width,
            final int height,
            final double[] row,
            final double[] transformed,
            final int[] argmin,
            final int[] envelopeIndex,
            final double[] envelopeLimit) {
        for (int y = 0; y < height; y++) {
            final int start = y * width;
            System.arraycopy(squared, start, row, 0, width);
            transform1D(row, width, transformed, argmin, envelopeIndex, envelopeLimit);
            for (int x = 0; x < width; x++) {
                final int seedColumn = argmin[x];
                final int cell = start + x;
                squared[cell] = transformed[x];
                spacing[cell] = seedSpacing[nearestRow[start + seedColumn] * width + seedColumn];
            }
        }
    }

    /**
     * Lower envelope of the parabolas {@code (q - v)² + f(v)}.
     *
     * <p>Each parabola contributes one contiguous interval, so a stack of intersection points
     * describes the whole envelope in linear time. That linear pass is what makes the transform
     * usable per pixel of a full texture layer.</p>
     */
    private static void transform1D(
            final double[] values,
            final int count,
            final double[] out,
            final int[] argmin,
            final int[] envelopeIndex,
            final double[] envelopeLimit) {
        int top = 0;
        envelopeIndex[0] = 0;
        envelopeLimit[0] = Double.NEGATIVE_INFINITY;
        envelopeLimit[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < count; q++) {
            double crossing = intersection(values, q, envelopeIndex[top]);
            while (crossing <= envelopeLimit[top]) {
                top--;
                crossing = intersection(values, q, envelopeIndex[top]);
            }
            top++;
            envelopeIndex[top] = q;
            envelopeLimit[top] = crossing;
            envelopeLimit[top + 1] = Double.POSITIVE_INFINITY;
        }
        int cursor = 0;
        for (int q = 0; q < count; q++) {
            while (envelopeLimit[cursor + 1] < q) {
                cursor++;
            }
            final int source = envelopeIndex[cursor];
            final double offset = q - source;
            out[q] = offset * offset + values[source];
            argmin[q] = source;
        }
    }

    private static double intersection(final double[] values, final int q, final int source) {
        final double numerator = (values[q] + (double) q * q) - (values[source] + (double) source * source);
        final double denominator = 2.0 * (q - source);
        return numerator / denominator;
    }
}
