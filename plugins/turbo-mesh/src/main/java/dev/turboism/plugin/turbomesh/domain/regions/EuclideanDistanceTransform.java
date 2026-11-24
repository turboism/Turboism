package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Exact 2D Euclidean distance transform via Felzenszwalb-Huttenlocher parabola envelopes.
 *
 * <p>Computes exact squared distances in linear time per axis so both interior distance grading
 * and silhouette bleed expansion share one accurate metric.</p>
 */
final class EuclideanDistanceTransform {

    private static final double INFINITY_MARGIN = 1.0;

    private EuclideanDistanceTransform() {}

    /**
     * Computes exact Euclidean distance from every cell to the nearest {@code true} cell.
     *
     * @param mask row-major solid occupancy mask
     * @param width raster width in cells
     * @param height raster height in cells
     * @return distances in pixels; {@code 0.0} on {@code true} cells
     */
    static double[] distanceFromMask(final boolean[] mask, final int width, final int height) {
        final int cells = width * height;
        final double unset = 4.0 * (width * (double) width + height * (double) height + INFINITY_MARGIN);
        final double[] squared = new double[cells];
        for (int i = 0; i < cells; i++) {
            squared[i] = mask[i] ? 0.0 : unset;
        }
        final int longest = Math.max(width, height);
        final double[] line = new double[longest];
        final double[] transformed = new double[longest];
        final int[] argmin = new int[longest];
        final int[] envelopeIndex = new int[longest + 1];
        final double[] envelopeLimit = new double[longest + 2];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                line[y] = squared[y * width + x];
            }
            transform1D(line, height, transformed, argmin, envelopeIndex, envelopeLimit);
            for (int y = 0; y < height; y++) {
                squared[y * width + x] = transformed[y];
            }
        }
        for (int y = 0; y < height; y++) {
            final int start = y * width;
            System.arraycopy(squared, start, line, 0, width);
            transform1D(line, width, transformed, argmin, envelopeIndex, envelopeLimit);
            for (int x = 0; x < width; x++) {
                squared[start + x] = Math.sqrt(transformed[x]);
            }
        }
        return squared;
    }

    /**
     * Computes the lower envelope of parabolas {@code (q - v)^2 + values[v]}.
     *
     * @param values input 1D squared values
     * @param count number of entries to process
     * @param out destination array for envelope values
     * @param argmin destination array for winning source indices
     * @param envelopeIndex scratch index stack of size at least {@code count + 1}
     * @param envelopeLimit scratch boundary array of size at least {@code count + 2}
     */
    static void transform1D(
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
