package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of a {@link EditObjectKind#ROTATION_DEFORMER} object, matching the official
 * {@code RotationDeformer} data block of {@code GetObject}.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditRotationDeformerData(
        String name,
        Optional<PartId> parentId,
        Optional<DeformerId> parentDeformerId,
        double angle,
        double baseAngle,
        double scale,
        double opacity,
        Optional<String> multiplyColor,
        Optional<String> screenColor,
        EditLabelColor labelColor,
        List<Point2> vertices)
        implements EditObjectData {

    public EditRotationDeformerData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(parentDeformerId, "parentDeformerId");
        if (!Double.isFinite(angle)
                || !Double.isFinite(baseAngle)
                || !Double.isFinite(scale)
                || !Double.isFinite(opacity)) {
            throw new IllegalArgumentException("angle, baseAngle, scale, opacity must be finite");
        }
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        Objects.requireNonNull(labelColor, "labelColor");
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.ROTATION_DEFORMER;
    }
}
