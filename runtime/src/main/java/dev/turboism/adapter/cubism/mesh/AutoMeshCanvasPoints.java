package dev.turboism.adapter.cubism.mesh;

import dev.turboism.automesh.AutoMeshWriteLayout;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts one plugin layout into the mesh-space geometry the Editor's own Auto Mesh writes.
 *
 * <p>The Editor's editable mesh shares the ArtMesh base position space: its generator places each
 * point there and the source keeps the same space in {@code getPositions}. The plugin plans in uv,
 * so every layout uv maps into that space through the ArtMesh's current uvs, base positions and
 * triangles. A raster or canvas transform would place the points outside the mesh.</p>
 *
 * <p>Edges are undirected and appear once. Native code adds them with an "if not exists" call, so
 * a pair shared by two triangles must not be offered twice.</p>
 */
final class AutoMeshCanvasPoints {

    /** Vertices one triangle names; every triangle block consumes this many indices. */
    private static final int TRIANGLE_VERTICES = 3;

    private final float[] pointsXY;
    private final int[] edgePairs;

    private AutoMeshCanvasPoints(final float[] pointsXY, final int[] edgePairs) {
        this.pointsXY = pointsXY;
        this.edgePairs = edgePairs;
    }

    /**
     * Converts one layout into base-space points and the unique triangle edges that reference them.
     *
     * @param layout the plugin layout
     * @param sourceUvs the ArtMesh uvs as {@code u0, v0, u1, v1, ...}
     * @param sourcePositions the ArtMesh base positions in the same vertex order
     * @param sourceIndices the ArtMesh triangle indices
     * @return the mesh-space geometry
     * @throws IllegalArgumentException when the source cannot describe finite mesh points
     */
    static AutoMeshCanvasPoints of(
            final AutoMeshWriteLayout layout,
            final float[] sourceUvs,
            final float[] sourcePositions,
            final int[] sourceIndices) {
        final AutoMeshUvMapping mapping =
                AutoMeshUvMapping.of(points(sourceUvs), points(sourcePositions), boxed(sourceIndices));
        final List<Point2> uvs = layout.geometry().uvs();
        final float[] points = new float[uvs.size() * 2];
        for (int index = 0; index < uvs.size(); index++) {
            final Point2 mapped = mapping.map(uvs.get(index).x(), uvs.get(index).y());
            if (!Float.isFinite(mapped.x()) || !Float.isFinite(mapped.y())) {
                throw new IllegalArgumentException("the layout resolves to a non-finite mesh point");
            }
            points[index * 2] = mapped.x();
            points[index * 2 + 1] = mapped.y();
        }
        return new AutoMeshCanvasPoints(points, uniqueEdges(layout.geometry().triangleIndices(), uvs.size()));
    }

    /** @return the canvas points as {@code x0, y0, x1, y1, ...} */
    float[] pointsXY() {
        return pointsXY.clone();
    }

    /** @return the undirected triangle edges as {@code first0, second0, first1, second1, ...} */
    int[] edgePairs() {
        return edgePairs.clone();
    }

    private static List<Point2> points(final float[] values) {
        if (values == null || (values.length & 1) != 0 || values.length < TRIANGLE_VERTICES * 2) {
            throw new IllegalArgumentException("the ArtMesh carries no usable coordinate pairs");
        }
        final List<Point2> points = new ArrayList<>(values.length / 2);
        for (int index = 0; index + 1 < values.length; index += 2) {
            points.add(new Point2(values[index], values[index + 1]));
        }
        return points;
    }

    private static List<Integer> boxed(final int[] values) {
        if (values == null || values.length < TRIANGLE_VERTICES) {
            throw new IllegalArgumentException("the ArtMesh carries no triangles");
        }
        final List<Integer> boxed = new ArrayList<>(values.length);
        for (final int value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    /**
     * Returns every triangle edge once, ordered by the triangle list.
     *
     * <p>An edge whose endpoints coincide carries no triangle and would make the host refuse the
     * whole layout, so it is dropped here instead of failing the write.</p>
     */
    private static int[] uniqueEdges(final List<Integer> triangleIndices, final int vertexCount) {
        final List<int[]> edges = new ArrayList<>();
        for (int block = 0; block + TRIANGLE_VERTICES <= triangleIndices.size(); block += TRIANGLE_VERTICES) {
            for (int side = 0; side < TRIANGLE_VERTICES; side++) {
                final int first = triangleIndices.get(block + side);
                final int second = triangleIndices.get(block + (side + 1) % TRIANGLE_VERTICES);
                requireVertex(first, vertexCount);
                requireVertex(second, vertexCount);
                if (first == second || contains(edges, first, second)) {
                    continue;
                }
                edges.add(new int[] {first, second});
            }
        }
        final int[] pairs = new int[edges.size() * 2];
        for (int index = 0; index < edges.size(); index++) {
            pairs[index * 2] = edges.get(index)[0];
            pairs[index * 2 + 1] = edges.get(index)[1];
        }
        return pairs;
    }

    private static boolean contains(final List<int[]> edges, final int first, final int second) {
        for (final int[] edge : edges) {
            if ((edge[0] == first && edge[1] == second) || (edge[0] == second && edge[1] == first)) {
                return true;
            }
        }
        return false;
    }

    private static void requireVertex(final int index, final int vertexCount) {
        if (index < 0 || index >= vertexCount) {
            throw new IllegalArgumentException("the layout names a triangle vertex outside its point range");
        }
    }
}
