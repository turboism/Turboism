package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed sparse values for one existing independent, non-default ordinary stored form. */
@CubismEditor(from = "5.0.00")
public record ArtMeshKeyformValues(
        List<ArtMeshAuthoringSnapshot.Coordinate> coordinates,
        String formGuid,
        Optional<List<Point2>> positions,
        Optional<Integer> drawOrder,
        Optional<Float> opacity) {
    public ArtMeshKeyformValues {
        coordinates = List.copyOf(coordinates);
        if (Objects.requireNonNull(formGuid, "formGuid").isBlank())
            throw new IllegalArgumentException("blank form GUID");
        positions = Objects.requireNonNull(positions, "positions").map(List::copyOf);
        Objects.requireNonNull(drawOrder, "drawOrder").ifPresent(value -> {
            if (value < 0 || value > 1000) throw new IllegalArgumentException("draw order outside 0..1000");
        });
        Objects.requireNonNull(opacity, "opacity").ifPresent(value -> {
            if (!Float.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException("ordinary opacity outside 0..1");
        });
        if (positions.isEmpty() && drawOrder.isEmpty() && opacity.isEmpty())
            throw new IllegalArgumentException("at least one stored value is required");
    }
}
