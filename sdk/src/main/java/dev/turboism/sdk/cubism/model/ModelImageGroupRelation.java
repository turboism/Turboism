package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable group projection; intentionally has no fabricated group GUID. */
public record ModelImageGroupRelation(
    ModelImageGroup group,
    List<ModelImageId> modelImageIds,
    List<RawImageId> linkedRawImageIds,
    Optional<Boolean> projectTreeVisible
) {
    public ModelImageGroupRelation {
        group = Objects.requireNonNull(group, "group");
        modelImageIds = List.copyOf(Objects.requireNonNull(modelImageIds, "modelImageIds"));
        linkedRawImageIds = List.copyOf(Objects.requireNonNull(linkedRawImageIds, "linkedRawImageIds"));
        projectTreeVisible = Objects.requireNonNull(projectTreeVisible, "projectTreeVisible");
    }

    /** Returns the group's display name, not a stable resource identity. */
    public String groupName() {
        return group.groupName();
    }

    /** Returns the memo captured in the group projection. */
    public String memo() {
        return group.memo();
    }
}
