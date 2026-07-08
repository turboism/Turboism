package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SelectionSummary(
    Optional<ProjectId> activeProjectId,
    Optional<DocumentId> activeDocumentId,
    Optional<ModelObjectId> activeModelId,
    List<ParameterId> selectedParameterIds,
    List<ArtMeshId> selectedArtMeshIds,
    List<DeformerId> selectedDeformerIds,
    List<ModelObjectId> selectedModelObjectIds
) {
    public SelectionSummary {
        activeProjectId = Objects.requireNonNull(activeProjectId, "activeProjectId");
        activeDocumentId = Objects.requireNonNull(activeDocumentId, "activeDocumentId");
        activeModelId = Objects.requireNonNull(activeModelId, "activeModelId");
        selectedParameterIds = List.copyOf(Objects.requireNonNull(selectedParameterIds, "selectedParameterIds"));
        selectedArtMeshIds = List.copyOf(Objects.requireNonNull(selectedArtMeshIds, "selectedArtMeshIds"));
        selectedDeformerIds = List.copyOf(Objects.requireNonNull(selectedDeformerIds, "selectedDeformerIds"));
        selectedModelObjectIds = List.copyOf(Objects.requireNonNull(selectedModelObjectIds, "selectedModelObjectIds"));
    }

    public static SelectionSummary empty() {
        return new SelectionSummary(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
