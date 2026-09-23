package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of a {@link EditObjectKind#WARP_DEFORMER} object, matching the official
 * {@code WarpDeformer} data block of {@code GetObject} (external API 1.1.0). The official
 * block reports a single bounding {@code Rectangle} (four named corner positions), not a
 * list of lattice cells; {@code BezierDivH}/{@code BezierDivV} are nullable and are absent
 * when the deformer has no bezier subdivision extension.
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
        Optional<Integer> bezierDivH,
        Optional<Integer> bezierDivV,
        EditLabelColor labelColor,
        EditRectangle rectangle)
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
        Objects.requireNonNull(bezierDivH, "bezierDivH");
        Objects.requireNonNull(bezierDivV, "bezierDivV");
        Objects.requireNonNull(labelColor, "labelColor");
        Objects.requireNonNull(rectangle, "rectangle");
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.WARP_DEFORMER;
    }
}
