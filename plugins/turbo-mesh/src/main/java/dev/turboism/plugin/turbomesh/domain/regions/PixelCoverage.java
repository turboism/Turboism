package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-centre coverage metrics of the planned domain.
 *
 * <p>The prototype rated silhouettes by rasterising the vector domain and comparing it with the
 * source alpha mask. The same comparison keeps the metric honest: a self-consistent mesh cannot
 * prove that the texture moved onto the intended pixels.</p>
 */
final class PixelCoverage {

    private PixelCoverage() {}

    /** Intersection over union of the solid alpha area and the pixels inside the vector domain. */
    static double alphaIou(final PolygonShape domain, final boolean[] mask, final int width, final int height) {
        if (domain == null) {
            return 0.0;
        }
        long intersection = 0L;
        long inside = 0L;
        for (int y = 0; y < height; y++) {
            final List<Double> crossings = new ArrayList<>();
            for (final Polyline ring : domain.rings()) {
                for (int i = 0; i < ring.segmentCount(); i++) {
                    final int next = ring.nextIndex(i);
                    final double ay = ring.y(i);
                    final double by = ring.y(next);
                    if ((ay > y) == (by > y)) {
                        continue;
                    }
                    final double t = (y - ay) / (by - ay);
                    crossings.add(ring.x(i) + t * (ring.x(next) - ring.x(i)));
                }
            }
            if (crossings.size() < 2) {
                continue;
            }
            crossings.sort(Double::compare);
            for (int i = 0; i + 1 < crossings.size(); i += 2) {
                final double left = crossings.get(i);
                final double right = crossings.get(i + 1);
                final int firstColumn = (int) Math.floor(left) + 1;
                final int lastColumn = (int) Math.ceil(right) - 1;
                for (int x = Math.max(0, firstColumn); x <= Math.min(width - 1, lastColumn); x++) {
                    if (x <= left || x >= right) {
                        continue;
                    }
                    inside++;
                    if (mask[y * width + x]) {
                        intersection++;
                    }
                }
            }
        }
        long solid = 0L;
        for (final boolean value : mask) {
            if (value) {
                solid++;
            }
        }
        final long union = solid + inside - intersection;
        return union == 0L ? 0.0 : (double) intersection / union;
    }
}
