package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;

/**
 * Generated mesh-space vertices and the undirected edge pairs that index them.
 *
 * <p>Pure data: no host objects, no canvas geometry. The layout is computed from the dialog state
 * and handed to the runtime canvas session, which owns projection and the transactional commit.</p>
 */
record MeshPointLayout(List<Point2> points, int[] edgePairs) {

    MeshPointLayout {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        Objects.requireNonNull(edgePairs, "edgePairs");
        if ((edgePairs.length & 1) != 0) {
            throw new IllegalArgumentException("edge pairs must have even length");
        }
        final int[] edges = edgePairs.clone();
        for (int vertex : edges) {
            if (vertex < 0 || vertex >= points.size()) {
                throw new IllegalArgumentException("edge pair names a vertex outside the layout");
            }
        }
        edgePairs = edges;
    }

    /** Returns the number of generated vertices. */
    int vertexCount() {
        return points.size();
    }

    /** Returns the number of generated undirected edges. */
    int edgeCount() {
        return edgePairs.length / 2;
    }

    @Override
    public int[] edgePairs() {
        return edgePairs.clone();
    }
}
