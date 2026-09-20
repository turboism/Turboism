package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.model.PartId;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of a {@link EditObjectKind#GLUE} object, matching the official {@code Glue} data
 * block of {@code GetObject}.
 */
public record EditGlueData(
        String name,
        Optional<PartId> parentId,
        double intensity,
        EditLabelColor labelColor)
        implements EditObjectData {

    public EditGlueData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentId, "parentId");
        if (!Double.isFinite(intensity)) {
            throw new IllegalArgumentException("intensity must be finite");
        }
        Objects.requireNonNull(labelColor, "labelColor");
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.GLUE;
    }
}
