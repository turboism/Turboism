package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.ParameterGroupId;
import java.util.List;
import java.util.Objects;

/**
 * A parameter group in the parameter-structure tree, matching the official {@code Group} entry of
 * {@code GetParameterStructure} — also the root type of the returned tree.
 */
public record EditParameterGroupNode(
        ParameterGroupId id,
        String name,
        EditLabelColor labelColor,
        List<EditParameterStructureEntry> children)
        implements EditParameterStructureEntry {

    public EditParameterGroupNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(labelColor, "labelColor");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
