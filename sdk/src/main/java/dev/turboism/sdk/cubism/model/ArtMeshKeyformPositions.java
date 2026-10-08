package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Objects;

/** One existing ordinary form's absolute positions in a compare-and-set batch. */
public record ArtMeshKeyformPositions(List<ArtMeshAuthoringSnapshot.Coordinate> coordinates,
        String formGuid, List<Point2> positions) {
    public ArtMeshKeyformPositions {
        coordinates = List.copyOf(coordinates);
        formGuid = Objects.requireNonNull(formGuid, "formGuid");
        if (formGuid.isBlank()) throw new IllegalArgumentException("blank form GUID");
        positions = List.copyOf(positions);
    }
}
