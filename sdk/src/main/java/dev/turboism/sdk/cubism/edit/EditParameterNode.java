package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;

/**
 * A parameter leaf in the parameter-structure tree, matching the official {@code Parameter} entry
 * of {@code GetParameterStructure}.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditParameterNode(
        ParameterId id,
        String name,
        double min,
        double defaultValue,
        double max,
        boolean repeat,
        boolean blendShape)
        implements EditParameterStructureEntry {

    public EditParameterNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (!Double.isFinite(min) || !Double.isFinite(defaultValue) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("min, defaultValue, max must be finite");
        }
    }
}
