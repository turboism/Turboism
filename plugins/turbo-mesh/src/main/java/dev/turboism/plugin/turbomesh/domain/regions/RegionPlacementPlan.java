package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable result of {@link RegionPlacementPlanner#plan}.
 *
 * <p>Vertex coordinates and constraint roles stay in source-image pixel space; {@code regions}
 * keeps the cropped label raster that produced them, so a caller can map any pixel back to the
 * region that owns it. Triangle indices refer to {@link #vertices()}.</p>
 *
 * <p>Domain rings are unoriented loops. Triangle membership follows nesting depth: a ring inside an
 * even number of other rings bounds material and an odd one bounds a hole, so disjoint opaque parts
 * each keep their own silhouette ring.</p>
 *
 * <p>{@code triangleIou} compares the rasterised triangles with the alpha mask of the source
 * texture. It therefore fails when the planner loses material, which an outline metric cannot.</p>
 *
 * <p>{@code fallback} names the stage the planner had to drop to satisfy the coverage contract, so
 * a caller and its logs can see that the result differs from the requested pipeline.</p>
 */
public record RegionPlacementPlan(
        List<Point2> vertices,
        int[] triangleIndices,
        List<Constraint> constraints,
        List<int[]> domainRings,
        RegionGrid regions,
        int vertexCountBeforeMerge,
        int vertexCountAfterMerge,
        double triangleIou,
        ShapeFamily shapeFamily,
        Fallback fallback) {

    public RegionPlacementPlan {
        vertices = List.copyOf(vertices);
        triangleIndices = triangleIndices.clone();
        constraints = List.copyOf(constraints);
        final List<int[]> rings = new ArrayList<>(domainRings.size());
        for (final int[] ring : domainRings) {
            rings.add(ring.clone());
        }
        domainRings = List.copyOf(rings);
    }

    @Override
    public int[] triangleIndices() {
        return triangleIndices.clone();
    }

    @Override
    public List<int[]> domainRings() {
        final List<int[]> copy = new ArrayList<>(domainRings.size());
        for (final int[] ring : domainRings) {
            copy.add(ring.clone());
        }
        return copy;
    }

    /** Number of triangles in the plan. */
    public int triangleCount() {
        return triangleIndices.length / 3;
    }

    /** One hard edge of the plan; {@code a} and {@code b} index {@link #vertices()}. */
    public record Constraint(int a, int b, ConstraintRole role) {}

    /** Planning stage that the planner had to drop to keep the coverage contract. */
    public enum Fallback {

        /** Every configured stage ran. */
        NONE,

        /** Near-point averaging would have folded the domain, so it did not run. */
        MERGE_DISABLED,

        /** Border simplification and near-point averaging both damaged the domain, so neither ran. */
        SIMPLIFICATION_DISABLED
    }

    /**
     * Cropped label raster of the selected region level.
     *
     * @param labels row-major labels, {@code 0} marks pixels outside the solid silhouette
     * @param originX source-image x of raster column {@code 0}
     * @param originY source-image y of raster row {@code 0}
     */
    public record RegionGrid(int[] labels, int width, int height, int originX, int originY) {

        public RegionGrid {
            if (labels.length != width * height) {
                throw new IllegalArgumentException(
                        "labels must hold " + (width * height) + " cells but held " + labels.length);
            }
            labels = labels.clone();
        }

        @Override
        public int[] labels() {
            return labels.clone();
        }

        /** Region label at a source-image pixel position, or {@code 0} outside the solid area. */
        public int labelAt(final double x, final double y) {
            final int column = (int) Math.floor(x) - originX;
            final int row = (int) Math.floor(y) - originY;
            if (column < 0 || row < 0 || column >= width || row >= height) {
                return 0;
            }
            return labels[row * width + column];
        }

        /** Number of distinct non-zero labels inside the solid area. */
        public int regionCount() {
            final boolean[] seen = new boolean[maxLabel() + 1];
            int count = 0;
            for (final int label : labels) {
                if (label > 0 && !seen[label]) {
                    seen[label] = true;
                    count++;
                }
            }
            return count;
        }

        private int maxLabel() {
            int max = 0;
            for (final int label : labels) {
                max = Math.max(max, label);
            }
            return max;
        }
    }
}
