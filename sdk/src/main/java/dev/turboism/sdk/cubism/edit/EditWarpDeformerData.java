package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of a {@link EditObjectKind#WARP_DEFORMER} object, matching the official
 * {@code WarpDeformer} data block of {@code GetObject}, including lattice geometry.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditWarpDeformerData(
        String name,
        Optional<PartId> parentId,
        Optional<DeformerId> parentDeformerId,
        double opacity,
        Optional<String> multiplyColor,
        Optional<String> screenColor,
        int warpDivH,
        int warpDivV,
        int bezierDivH,
        int bezierDivV,
        EditLabelColor labelColor,
        List<EditRectangle> rectangles)
        implements EditObjectData {

    public EditWarpDeformerData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(parentDeformerId, "parentDeformerId");
        if (!Double.isFinite(opacity)) {
            throw new IllegalArgumentException("opacity must be finite");
        }
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        Objects.requireNonNull(labelColor, "labelColor");
        rectangles = List.copyOf(Objects.requireNonNull(rectangles, "rectangles"));
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.WARP_DEFORMER;
    }
}
