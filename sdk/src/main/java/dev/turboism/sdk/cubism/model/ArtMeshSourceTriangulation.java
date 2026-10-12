package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;

/** Owned result from the Editor's source-plane triangulator. This is a planning
 * observation, not proof that a mesh has entered or committed the native editor. */
@CubismEditor(from = "5.0.00")
public record ArtMeshSourceTriangulation(List<Point2> positions, List<Integer> triangleIndices) {
    public ArtMeshSourceTriangulation {
        positions = List.copyOf(positions);
        triangleIndices = List.copyOf(triangleIndices);
        if (triangleIndices.size() % 3 != 0) {
            throw new IllegalArgumentException("Triangle indices must contain complete faces.");
        }
        for (int index : triangleIndices) {
            if (index < 0 || index >= positions.size()) {
                throw new IllegalArgumentException("Triangulated index is outside the point domain.");
            }
        }
    }
}
