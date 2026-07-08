package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
