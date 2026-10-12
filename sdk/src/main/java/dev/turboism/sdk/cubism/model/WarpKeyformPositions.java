package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import java.util.Objects;

/** Absolute control points for one existing, independent ordinary Warp grid cell. */
@CubismEditor(from = "5.0.00")
public record WarpKeyformPositions(
        List<WarpAuthoringSnapshot.Coordinate> coordinates, String formGuid, List<Point2> controlPoints) {
    public WarpKeyformPositions {
        coordinates = List.copyOf(coordinates);
        controlPoints = List.copyOf(controlPoints);
        if (Objects.requireNonNull(formGuid, "formGuid").isBlank())
            throw new IllegalArgumentException("blank Warp form GUID");
    }
}
