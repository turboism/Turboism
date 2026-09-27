package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;

/**
 * One node of a palette-structure tree returned by {@code GetPartStructure} or
 * {@code GetDeformerStructure}.
 *
 * @param name the object's display name
 * @param id the editor-assigned object id
 * @param kind the object type
 * @param children child nodes in palette order; empty for leaf objects
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditObjectNode(
        String name,
        ModelObjectId id,
        EditObjectKind kind,
        List<EditObjectNode> children) {

    public EditObjectNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
