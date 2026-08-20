package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node in a snapshot of the Editor's model tree.
 *
 * <p>A node is a value, not a live handle: it records the parent and child links as they stood when
 * the hierarchy was captured and does not track later Editor edits. {@code childIds} is defensively
 * copied and unmodifiable, and a child id may refer to a node absent from the snapshot.
 *
 * @param id stable identifier of the model object this node stands for
 * @param name the Editor-assigned display name; may be empty but never {@code null}
 * @param kind what sort of model object this is, {@link Kind#UNKNOWN} when the host reported a type
 *             this SDK does not model
 * @param parentId the containing node, empty for the root
 * @param childIds ids of the node's direct children in Editor order; unmodifiable, possibly empty
 */
public record HierarchyNode(
    ModelObjectId id,
    String name,
    Kind kind,
    Optional<ModelObjectId> parentId,
    List<ModelObjectId> childIds
) {
    public HierarchyNode {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        parentId = Objects.requireNonNull(parentId, "parentId");
        childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
    }

    public enum Kind {
        MODEL,
        PARAMETER,
        ART_MESH,
        DEFORMER,
        GROUP,
        PART,
        UNKNOWN
    }
}
