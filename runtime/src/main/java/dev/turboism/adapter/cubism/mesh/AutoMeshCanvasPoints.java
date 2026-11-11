package dev.turboism.adapter.cubism.mesh;

import dev.turboism.automesh.AutoMeshWriteLayout;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts one plugin layout into the canvas geometry the Editor's own Auto Mesh writes.
 *
 * <p>The Editor's generator writes canvas points into the editable mesh and derives uvs, keyforms
 * and triangulation from them. A plugin layout therefore travels back through the published
 * uv-to-pixel affine of the texture it was planned on, and the resulting image-local pixel reaches
 * the canvas through the model image transform. A geometry-only run has no affine, so its uvs scale
 * by the image extent, which is the documented shape of the default affine.</p>
 *
 * <p>Edges are undirected and appear once. Native code adds them with an "if not exists" call, so
 * a pair shared by two triangles must not be offered twice.</p>
 */
final class AutoMeshCanvasPoints {

    /** Components of the row-major 2x3 affine the conversion consumes. */
    private static final int AFFINE_COMPONENTS = 6;

    /** Vertices one triangle names; every triangle block consumes this many indices. */
    private static final int TRIANGLE_VERTICES = 3;

    private final float[] pointsXY;
    private final int[] edgePairs;

    private AutoMeshCanvasPoints(final float[] pointsXY, final int[] edgePairs) {
        this.pointsXY = pointsXY;
        this.edgePairs = edgePairs;
    }

    /**
     * Converts one layout into canvas points and the unique triangle edges that reference them.
     *
     * @param layout the plugin layout and the texture it was planned on
     * @param imageExtent the model image pixel extent, used when the layout carries no texture
     * @param localToCanvas the row-major 2x3 model-image-local to canvas affine
     * @return the canvas geometry
     * @throws IllegalArgumentException when the inputs cannot describe finite canvas points
     */
    static AutoMeshCanvasPoints of(
            final AutoMeshWriteLayout layout, final double[] imageExtent, final double[] localToCanvas) {
        requireAffine(localToCanvas, "local-to-canvas");
        final List<Point2> uvs = layout.geometry().uvs();
        final float[] points = new float[uvs.size() * 2];
        final double[] pixel = new double[2];
        for (int index = 0; index < uvs.size(); index++) {
            pixelOf(layout.texture().orElse(null), imageExtent, uvs.get(index), pixel);
            final double x = localToCanvas[0] * pixel[0] + localToCanvas[1] * pixel[1] + localToCanvas[2];
            final double y = localToCanvas[3] * pixel[0] + localToCanvas[4] * pixel[1] + localToCanvas[5];
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("the layout resolves to a non-finite canvas point");
            }
            points[index * 2] = (float) x;
            points[index * 2 + 1] = (float) y;
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

    private static void pixelOf(
            final TexturePixels texture, final double[] imageExtent, final Point2 uv, final double[] out) {
        if (texture != null) {
            texture.uvToPixel(uv.x(), uv.y(), out);
            return;
        }
        if (imageExtent == null || imageExtent.length != 2) {
            throw new IllegalArgumentException("a geometry-only layout needs the image pixel extent");
        }
        out[0] = uv.x() * imageExtent[0];
        out[1] = uv.y() * imageExtent[1];
    }

    private static void requireAffine(final double[] values, final String name) {
        if (values == null || values.length != AFFINE_COMPONENTS) {
            throw new IllegalArgumentException(name + " must carry " + AFFINE_COMPONENTS + " components");
        }
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
