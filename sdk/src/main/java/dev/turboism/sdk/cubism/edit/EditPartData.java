package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of a {@link EditObjectKind#PART} object, matching the official {@code Part} data
 * block of {@code GetObject}.
 */
public record EditPartData(
        String name,
        Optional<PartId> parentId,
        boolean grouped,
        boolean guidImage,
        boolean offscreen,
        List<ModelObjectId> clippingIds,
        boolean reverseMask,
        int drawOrder,
        double opacity,
        Optional<String> multiplyColor,
        Optional<String> screenColor,
        EditColorBlend colorBlend,
        EditAlphaBlend alphaBlend,
        EditLabelColor labelColor)
        implements EditObjectData {

    public EditPartData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentId, "parentId");
        clippingIds = List.copyOf(Objects.requireNonNull(clippingIds, "clippingIds"));
        if (!Double.isFinite(opacity)) {
            throw new IllegalArgumentException("opacity must be finite");
        }
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        Objects.requireNonNull(colorBlend, "colorBlend");
        Objects.requireNonNull(alphaBlend, "alphaBlend");
        Objects.requireNonNull(labelColor, "labelColor");
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.PART;
    }
}
