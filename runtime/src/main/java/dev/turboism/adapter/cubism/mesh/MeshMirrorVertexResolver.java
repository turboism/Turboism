package dev.turboism.adapter.cubism.mesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared vertex matching; host references, coordinate transforms and undo stay with the adapters. */
public final class MeshMirrorVertexResolver {
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
        if (!Float.isFinite(tolerance) || tolerance <= 0) throw new IllegalArgumentException("invalid tolerance");
        Map<Cell, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            MeshMirrorGeometry.Point point = points.get(i);
            if (!Float.isFinite(point.x()) || !Float.isFinite(point.y()))
                throw new IllegalArgumentException("invalid point");
            cells.computeIfAbsent(cell(point, tolerance), ignored -> new ArrayList<>())
                    .add(i);
        }
        int[] nearest = new int[points.size()];
        Arrays.fill(nearest, -1);
        double limit = (double) tolerance * tolerance;
        for (int i = 0; i < points.size(); i++) {
            MeshMirrorGeometry.Point p = points.get(i);
            MeshMirrorGeometry.Point reflected = MeshMirrorGeometry.reflect(axis, p.x(), p.y());
            Cell cell = cell(reflected, tolerance);
            double best = limit;
            boolean ambiguous = false;
            for (long x = cell.x - 1; x <= cell.x + 1; x++) {
                for (long y = cell.y - 1; y <= cell.y + 1; y++) {
                    for (int j : cells.getOrDefault(new Cell(x, y), List.of())) {
                        MeshMirrorGeometry.Point candidate = points.get(j);
                        double dx = reflected.x() - candidate.x(), dy = reflected.y() - candidate.y();
                        double value = dx * dx + dy * dy;
                        if (value < best - 1e-12) {
                            nearest[i] = j;
                            best = value;
                            ambiguous = false;
                        } else if (Math.abs(value - best) <= 1e-12) ambiguous = true;
                    }
                }
            }
            if (ambiguous) nearest[i] = -1;
        }
        int[] pairs = new int[points.size()];
        Arrays.fill(pairs, -1);
        for (int i = 0; i < pairs.length; i++) {
            int j = nearest[i];
            if (j >= 0 && j != i && nearest[j] == i) pairs[i] = j;
        }
        return pairs;
    }

    private record Cell(long x, long y) {}

    private static Cell cell(MeshMirrorGeometry.Point p, float size) {
        double x = Math.floor(p.x() / (double) size), y = Math.floor(p.y() / (double) size);
        if (Math.abs(x) > 9e18 || Math.abs(y) > 9e18) throw new IllegalArgumentException("coordinate range");
        return new Cell((long) x, (long) y);
    }
}
