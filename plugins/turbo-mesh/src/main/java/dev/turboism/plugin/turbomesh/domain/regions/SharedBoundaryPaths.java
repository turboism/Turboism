package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Extracts every shared pixel border exactly once as polyline chains.
 *
 * <p>Region borders are cut into chains at junctions, then handed to
 * {@link CurvatureBoundarySampler}, which locks the junctions and the sharp corners and resamples
 * the spans between them. Each chain keeps the pair of labels it separates, so the mesh builder
 * never has to guess which regions two neighbouring cells belong to.</p>
 *
 * <p>Every segment is oriented with the lower label of its pair on the algebraic left. That
 * orientation makes each border a directed chain, so a junction has exactly one continuation:
 * the one that keeps the same face on the left. An arbitrary pairing at a junction folds the
 * silhouette into a spiral and loses the thin spikes of high-frequency artwork.</p>
 */
final class SharedBoundaryPaths {

    private SharedBoundaryPaths() {}

    /**
     * @param labels region labels of the selected level, {@code 0} outside the silhouette
     * @param tolerance chord deviation budget of the curve resampling, in pixels
     * @param minSpacing lower bound of the emitted vertex distance in pixels
     * @param spacing upper bound of the distance between consecutive emitted points
     */
    static List<BoundaryPath> extract(
            final int[] labels,
            final int width,
            final int height,
            final double tolerance,
            final double minSpacing,
            final double spacing) {
        final Map<Long, List<int[]>> groups = new TreeMap<>();
        collectHorizontal(labels, width, height, groups);
        collectVertical(labels, width, height, groups);
        final List<BoundaryPath> paths = new ArrayList<>();
        for (final Map.Entry<Long, List<int[]>> group : groups.entrySet()) {
            final int labelA = (int) (group.getKey() >>> 32);
            final int labelB = (int) (group.getKey() & 0xFFFF_FFFFL);
            for (final int[] chain :
                    DirectedBoundary.chains(group.getValue(), node -> node % (width + 1), node -> node / (width + 1))) {
                final Polyline line =
                        CurvatureBoundarySampler.sample(toPolyline(chain, width + 1), tolerance, minSpacing, spacing);
                if (line.size() >= 2 && line.length() > 0.0) {
                    paths.add(new BoundaryPath(labelA, labelB, line));
                }
            }
        }
        return paths;
    }

    private static void collectHorizontal(
            final int[] labels, final int width, final int height, final Map<Long, List<int[]>> groups) {
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                final int above = y > 0 ? labels[(y - 1) * width + x] : 0;
                final int below = y < height ? labels[y * width + x] : 0;
                if (above != below) {
                    // The collected direction is +x, so the algebraically left pixel is "below".
                    add(groups, above, below, false, y * (width + 1) + x, y * (width + 1) + x + 1);
                }
            }
        }
    }

    private static void collectVertical(
            final int[] labels, final int width, final int height, final Map<Long, List<int[]>> groups) {
        for (int x = 0; x <= width; x++) {
            for (int y = 0; y < height; y++) {
                final int left = x > 0 ? labels[y * width + x - 1] : 0;
                final int right = x < width ? labels[y * width + x] : 0;
                if (left != right) {
                    // The collected direction is +y, so the algebraically left pixel is "left".
                    add(groups, left, right, true, y * (width + 1) + x, (y + 1) * (width + 1) + x);
                }
            }
        }
    }

    /**
     * Registers one border segment with the lower label on the algebraic left.
     *
     * @param firstIsLeft whether the first label sits on the algebraic left of {@code from -> to}
     */
    private static void add(
            final Map<Long, List<int[]>> groups,
            final int first,
            final int second,
            final boolean firstIsLeft,
            final int from,
            final int to) {
        final int low = Math.min(first, second);
        final int high = Math.max(first, second);
        final boolean lowIsLeft = firstIsLeft == (first == low);
        final int[] segment = lowIsLeft ? new int[] {from, to} : new int[] {to, from};
        groups.computeIfAbsent(((long) low << 32) | (high & 0xFFFF_FFFFL), ignored -> new ArrayList<>())
                .add(segment);
    }

    /** Maps corner-lattice nodes to pixel-centre coordinates. */
    private static Polyline toPolyline(final int[] chain, final int cornerWidth) {
        final double[] xs = new double[chain.length];
        final double[] ys = new double[chain.length];
        for (int i = 0; i < chain.length; i++) {
            xs[i] = (chain[i] % cornerWidth) - 0.5;
            ys[i] = (chain[i] / cornerWidth) - 0.5;
        }
        final boolean closed = chain.length > 2 && chain[0] == chain[chain.length - 1];
        return closed ? Polyline.closedFromRepeated(xs, ys) : new Polyline(xs, ys, false);
    }
}
