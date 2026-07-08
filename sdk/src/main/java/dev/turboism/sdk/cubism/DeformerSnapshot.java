package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DeformerSnapshot(
    String id,
    String name,
    DeformerType type,
    Optional<String> parentId,
    List<String> childIds
) implements ModelObjectSnapshot {
    public DeformerSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        parentId = Objects.requireNonNull(parentId, "parentId");
        childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
    }
}
