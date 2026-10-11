package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Objects;
import dev.turboism.sdk.CubismEditor;

/** Absolute rotation values for one existing, independent ordinary grid cell. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record RotationKeyformForm(List<RotationAuthoringSnapshot.Coordinate> coordinates,
        String formGuid, RotationDeformerForm value) {
    public RotationKeyformForm {
        coordinates = List.copyOf(coordinates); Objects.requireNonNull(value, "value");
        if (Objects.requireNonNull(formGuid, "formGuid").isBlank()) throw new IllegalArgumentException("blank Rotation form GUID");
    }
}
