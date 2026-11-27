package dev.turboism.adapter.cubism.optimization.atlas;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * Partitions ordered atlas tiles into painter-order-preserving waves whose destination bounding
 * boxes within each wave are strictly disjoint.
 */
public final class DisjointTileWavePlanner {

    private static final int MATRIX_LEN = 6;
    private static final int EMPTY_AREA = 0;

    private DisjointTileWavePlanner() {}

    /** Clipped integer bounding box {@code [x0, y0, x1, y1)} of a transformed tile on the page. */
    public record TileBounds(int x0, int y0, int x1, int y1) {

        /** Empty sentinel box when a tile lies completely outside the target atlas page. */
        public static final TileBounds EMPTY = new TileBounds(0, 0, 0, 0);

        public boolean isEmpty() {
            return x0 >= x1 || y0 >= y1;
        }

        public long areaPx() {
            return isEmpty() ? EMPTY_AREA : (long) (x1 - x0) * (y1 - y0);
        }

        public boolean intersects(final TileBounds other) {
            return !isEmpty() && !other.isEmpty() && x0 < other.x1 && x1 > other.x0 && y0 < other.y1 && y1 > other.y0;
        }
    }

    /**
     * Computes a conservative clipped integer bounding box covering all pixels a transformed tile
     * with optional padding can modify on the destination canvas.
     *
     * @param srcW source width in pixels
     * @param srcH source height in pixels
     * @param transform affine transform applied to the tile
     * @param padPx extra margin in destination pixels (0 for exact grid blits, >= 3 for filtered)
     * @param pageW destination page width
     * @param pageH destination page height
     * @return clamped destination bounds or {@link TileBounds#EMPTY}
     */
    public static TileBounds computeClippedBounds(
            final int srcW,
            final int srcH,
            final AffineTransform transform,
            final int padPx,
            final int pageW,
            final int pageH) {
        if (srcW <= 0 || srcH <= 0 || pageW <= 0 || pageH <= 0 || transform == null) {
            return TileBounds.EMPTY;
        }
        final double[] m = new double[MATRIX_LEN];
        transform.getMatrix(m);
        final double m00 = m[0];
        final double m10 = m[1];
        final double m01 = m[2];
        final double m11 = m[3];
        final double m02 = m[4];
        final double m12 = m[5];
        if (!Double.isFinite(m00)
                || !Double.isFinite(m10)
                || !Double.isFinite(m01)
                || !Double.isFinite(m11)
                || !Double.isFinite(m02)
                || !Double.isFinite(m12)) {
            return new TileBounds(0, 0, pageW, pageH);
        }
        final double x00 = m02;
        final double y00 = m12;
        final double x10 = m00 * srcW + m02;
        final double y10 = m10 * srcW + m12;
        final double x01 = m01 * srcH + m02;
        final double y01 = m11 * srcH + m12;
        final double x11 = m00 * srcW + m01 * srcH + m02;
        final double y11 = m10 * srcW + m11 * srcH + m12;

        final double minX = Math.min(Math.min(x00, x10), Math.min(x01, x11));
        final double maxX = Math.max(Math.max(x00, x10), Math.max(x01, x11));
        final double minY = Math.min(Math.min(y00, y10), Math.min(y01, y11));
        final double maxY = Math.max(Math.max(y00, y10), Math.max(y01, y11));

        final int margin = Math.max(0, padPx);
        final int bx0 = Math.max(0, (int) Math.floor(minX) - margin);
        final int by0 = Math.max(0, (int) Math.floor(minY) - margin);
        final int bx1 = Math.min(pageW, (int) Math.ceil(maxX) + margin);
        final int by1 = Math.min(pageH, (int) Math.ceil(maxY) + margin);
        if (bx0 >= bx1 || by0 >= by1) {
            return TileBounds.EMPTY;
        }
        return new TileBounds(bx0, by0, bx1, by1);
    }

    /**
     * Groups tile indices {@code 0..boxes.size()-1} into sequential waves so all tiles inside any
     * single wave have pairwise disjoint bounds while every overlapping earlier tile finishes in an
     * earlier wave.
     *
     * @param boxes ordered destination bounds in painter's order
     * @return list of disjoint tile-index waves in execution order
     */
    public static List<int[]> planWaves(final List<TileBounds> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        final int count = boxes.size();
        final int[] assignedWave = new int[count];
        int maxWave = 0;

        for (int j = 0; j < count; j++) {
            final TileBounds current = boxes.get(j);
            int wave = 0;
            if (!current.isEmpty()) {
                for (int i = 0; i < j; i++) {
                    if (current.intersects(boxes.get(i))) {
                        wave = Math.max(wave, assignedWave[i] + 1);
                    }
                }
            }
            assignedWave[j] = wave;
            if (wave > maxWave) {
                maxWave = wave;
            }
        }

        final int waveCount = maxWave + 1;
        final List<List<Integer>> buckets = new ArrayList<>(waveCount);
        for (int w = 0; w < waveCount; w++) {
            buckets.add(new ArrayList<>());
        }
        for (int idx = 0; idx < count; idx++) {
            if (!boxes.get(idx).isEmpty()) {
                buckets.get(assignedWave[idx]).add(idx);
            }
        }

        final List<int[]> waves = new ArrayList<>(waveCount);
        for (final List<Integer> bucket : buckets) {
            if (!bucket.isEmpty()) {
                final int[] arr = new int[bucket.size()];
                for (int k = 0; k < bucket.size(); k++) {
                    arr[k] = bucket.get(k);
                }
                waves.add(arr);
            }
        }
        return waves;
    }
}
