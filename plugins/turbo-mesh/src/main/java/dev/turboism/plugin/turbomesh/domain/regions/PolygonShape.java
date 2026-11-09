package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Closed material region with islands and holes, expressed in source-pixel coordinates.
 *
 * <p>The shape owns a disjoint ring set rather than one silhouette plus holes: two opaque parts of
 * one layer are both material, and a hole inside one of them is empty. Membership therefore follows
 * the even-odd rule over every ring, which is the same rule the triangulator classifies triangles
 * with.</p>
 */
final class PolygonShape {

    private final List<Polyline> rings;
    private final boolean[] materialRing;

    /**
     * @param rings closed rings in any order; nesting depth decides which of them bound material
     */
    PolygonShape(final List<Polyline> rings) {
        for (final Polyline ring : rings) {
            if (!ring.closed()) {
                throw new IllegalArgumentException("every domain ring must be closed");
            }
        }
        this.rings = List.copyOf(rings);
        final List<double[][]> coordinates = new ArrayList<>(rings.size());
        for (final Polyline ring : rings) {
            final double[] xs = new double[ring.size()];
            final double[] ys = new double[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                xs[i] = ring.x(i);
                ys[i] = ring.y(i);
            }
            coordinates.add(new double[][] {xs, ys});
        }
        final int[] depths = RingNesting.depths(coordinates);
        this.materialRing = new boolean[rings.size()];
        for (int i = 0; i < materialRing.length; i++) {
            materialRing[i] = depths[i] % 2 == 0;
        }
    }

    /** Every ring of the shape, in construction order. */
    List<Polyline> rings() {
        return rings;
    }

    /** Enclosed material area in square pixels; holes subtract and islands add. */
    double area() {
        double area = 0.0;
        for (int i = 0; i < rings.size(); i++) {
            final double ringArea = Math.abs(rings.get(i).signedArea());
            area += materialRing[i] ? ringArea : -ringArea;
        }
        return area;
    }

    /**
     * Material membership of every raster cell centre.
     *
     * <p>Interior sampling asks this question per pixel, and a point-by-point ring test would make
     * that quadratic in the border length. One scanline pass over the rings answers the whole raster
     * with the same even-odd rule.</p>
     */
    boolean[] rasterize(final int width, final int height) {
        final boolean[] mask = new boolean[width * height];
        int segmentCount = 0;
        for (final Polyline ring : rings) {
            segmentCount += ring.segmentCount();
        }
        final double[] crossings = new double[segmentCount];
        for (int row = 0; row < height; row++) {
            final double scanY = row;
            int found = 0;
            for (final Polyline ring : rings) {
                for (int i = 0; i < ring.segmentCount(); i++) {
                    final int next = ring.nextIndex(i);
                    final double y0 = ring.y(i);
                    final double y1 = ring.y(next);
                    if ((y0 > scanY) == (y1 > scanY)) {
                        continue;
                    }
                    final double x0 = ring.x(i);
                    crossings[found++] = x0 + (scanY - y0) * (ring.x(next) - x0) / (y1 - y0);
                }
            }
            if (found == 0) {
                continue;
            }
            Arrays.sort(crossings, 0, found);
            for (int i = 0; i + 1 < found; i += 2) {
                final int from = Math.max(0, (int) Math.ceil(crossings[i]));
                final int to = Math.min(width - 1, (int) Math.floor(crossings[i + 1]));
                for (int column = from; column <= to; column++) {
                    mask[row * width + column] = true;
                }
            }
        }
        return mask;
    }
}
