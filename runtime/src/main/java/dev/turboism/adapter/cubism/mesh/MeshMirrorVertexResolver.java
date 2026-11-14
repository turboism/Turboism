package dev.turboism.adapter.cubism.mesh;

import java.util.Arrays;
import java.util.List;

/** Shared vertex matching; host references, coordinate transforms and undo stay with the adapters. */
public final class MeshMirrorVertexResolver {
    private static final double TIE_EPSILON = 1e-12;
    private static final double MAX_CELL_INDEX = 9e18;
    private static final int MIN_HASH_BUCKETS = 16;

    private MeshMirrorVertexResolver() {}

    /** Computes the distance from a candidate to a reflected source vertex. */
    @FunctionalInterface
    public interface Distance<T> {
        /** Returns the candidate distance, or a non-finite value when it cannot be measured. */
        double from(T candidate) throws ReflectiveOperationException;
    }

    /** Preserves the native topology editor's first-nearest and strict tolerance semantics. */
    public static <T> T nearest(Iterable<T> candidates, float tolerance, Distance<T> distance)
            throws ReflectiveOperationException {
        T nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (T candidate : candidates) {
            double value = distance.from(candidate);
            if (value >= 0 && value < best) {
                best = value;
                nearest = candidate;
            }
        }
        return nearest != null && best < tolerance ? nearest : null;
    }

    /**
     * Builds mutual, unambiguous pairs for a fixed gesture snapshot. On-axis points map to -1.
     * A tolerance-sized spatial index avoids scanning the entire mesh for each vertex.
     */
    public static int[] pairs(List<MeshMirrorGeometry.Point> points, MeshMirrorGeometry.Line axis, float tolerance) {
        int count = points.size();
        float[] xy = new float[count * 2];
        for (int i = 0; i < count; i++) {
            MeshMirrorGeometry.Point point = points.get(i);
            xy[i * 2] = point.x();
            xy[i * 2 + 1] = point.y();
        }
        return pairs(
                xy,
                count,
                axis.anchor().x(),
                axis.anchor().y(),
                axis.direction().x(),
                axis.direction().y(),
                tolerance);
    }

    /**
     * Builds mutual, unambiguous pairs directly from packed {@code [x0, y0, x1, y1, ...]} coordinates
     * using primitive spatial-hash buckets to avoid per-vertex object allocations.
     */
    public static int[] pairs(
            float[] xy, int count, float anchorX, float anchorY, float dirX, float dirY, float tolerance) {
        if (!Float.isFinite(tolerance) || tolerance <= 0) throw new IllegalArgumentException("invalid tolerance");
        if (xy == null || count < 0 || xy.length < count * 2) throw new IllegalArgumentException("invalid points");
        if (count == 0) return new int[0];

        int bucketCount = tableSize(count);
        int mask = bucketCount - 1;
        int[] head = new int[bucketCount];
        Arrays.fill(head, -1);
        int[] next = new int[count];
        long[] cellX = new long[count];
        long[] cellY = new long[count];
        double cellSize = tolerance;

        for (int i = 0; i < count; i++) {
            float px = xy[i * 2];
            float py = xy[i * 2 + 1];
            if (!Float.isFinite(px) || !Float.isFinite(py)) throw new IllegalArgumentException("invalid point");
            long cx = cellCoord(px, cellSize);
            long cy = cellCoord(py, cellSize);
            cellX[i] = cx;
            cellY[i] = cy;
            int slot = cellHash(cx, cy) & mask;
            next[i] = head[slot];
            head[slot] = i;
        }

        int[] nearest = new int[count];
        Arrays.fill(nearest, -1);
        double limit = (double) tolerance * tolerance;
        float normalX = -dirY;
        float normalY = dirX;

        for (int i = 0; i < count; i++) {
            float px = xy[i * 2];
            float py = xy[i * 2 + 1];
            float signedDistance = (px - anchorX) * normalX + (py - anchorY) * normalY;
            float rx = px - 2.0f * signedDistance * normalX;
            float ry = py - 2.0f * signedDistance * normalY;
            long rcx = cellCoord(rx, cellSize);
            long rcy = cellCoord(ry, cellSize);
            double best = limit;
            boolean ambiguous = false;
            for (long x = rcx - 1; x <= rcx + 1; x++) {
                for (long y = rcy - 1; y <= rcy + 1; y++) {
                    for (int j = head[cellHash(x, y) & mask]; j >= 0; j = next[j]) {
                        if (cellX[j] != x || cellY[j] != y) continue;
                        double dx = rx - xy[j * 2];
                        double dy = ry - xy[j * 2 + 1];
                        double value = dx * dx + dy * dy;
                        if (value < best - TIE_EPSILON) {
                            nearest[i] = j;
                            best = value;
                            ambiguous = false;
                        } else if (Math.abs(value - best) <= TIE_EPSILON) {
                            ambiguous = true;
                        }
                    }
                }
            }
            if (ambiguous) nearest[i] = -1;
        }

        int[] pairs = new int[count];
        Arrays.fill(pairs, -1);
        for (int i = 0; i < count; i++) {
            int j = nearest[i];
            if (j >= 0 && j != i && nearest[j] == i) pairs[i] = j;
        }
        return pairs;
    }

    private static int tableSize(int count) {
        int desired = Math.max(MIN_HASH_BUCKETS, count * 2);
        int highest = Integer.highestOneBit(desired - 1) << 1;
        return highest > 0 ? highest : 1 << 30;
    }

    private static int cellHash(long x, long y) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL;
        return (int) (h ^ (h >>> 32));
    }

    private static long cellCoord(float value, double cellSize) {
        double scaled = Math.floor(value / cellSize);
        if (Math.abs(scaled) > MAX_CELL_INDEX) throw new IllegalArgumentException("coordinate range");
        return (long) scaled;
    }
}
