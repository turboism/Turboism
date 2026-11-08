package dev.turboism.plugin.turbomesh.domain.refine;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps newly subdivided mesh vertices onto alternative parameter keyform geometries using
 * barycentric interpolation within each vertex's originating triangle.
 *
 * <p>Ensures 100% deformation fidelity across all parameter keyforms: existing vertices stay
 * exactly at their keyform positions, while new vertices naturally follow the linear deformation
 * field of the enclosing triangle.</p>
 */
public final class KeyformBarycentricMapper {

    private KeyformBarycentricMapper() {}

    /**
     * Interpolates positions for all subdivided vertices across a given keyform's vertex positions.
     *
     * @param originalKeyformPositions vertex positions of the ArtMesh under a specific keyform
     *     before refinement; length must match the original vertex count
     * @param weights barycentric weights produced by {@link IncrementalMeshRefiner}
     * @return extended position list containing original positions followed by interpolated positions
     */
    public static List<Point2> mapKeyform(
            final List<Point2> originalKeyformPositions, final List<IncrementalMeshRefiner.BarycentricWeight> weights) {
        Objects.requireNonNull(originalKeyformPositions, "originalKeyformPositions");
        Objects.requireNonNull(weights, "weights");

        final List<Point2> mapped = new ArrayList<>(originalKeyformPositions.size() + weights.size());
        mapped.addAll(originalKeyformPositions);

        for (final IncrementalMeshRefiner.BarycentricWeight weight : weights) {
            mapped.add(weight.interpolate(originalKeyformPositions));
        }

        return mapped;
    }
}
