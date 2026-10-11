package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Objects;
import dev.turboism.sdk.CubismEditor;

/** Absolute control points for one existing, independent ordinary Warp grid cell. */
@CubismEditor({"5.3.02", "5.3.03"})
public record WarpKeyformPositions(List<WarpAuthoringSnapshot.Coordinate> coordinates,
        String formGuid, List<Point2> controlPoints) {
    public WarpKeyformPositions {
        coordinates = List.copyOf(coordinates); controlPoints = List.copyOf(controlPoints);
        if (Objects.requireNonNull(formGuid, "formGuid").isBlank()) throw new IllegalArgumentException("blank Warp form GUID");
    }
}
