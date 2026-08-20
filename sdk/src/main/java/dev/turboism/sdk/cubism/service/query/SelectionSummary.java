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

/**
 * An immutable snapshot of the Editor's selection and active context.
 *
 * <p>Every list is defensively copied and unmodifiable, so the summary is unaffected by later
 * selection changes. The typed lists and {@code selectedModelObjectIds} are separate views supplied
 * by the host; this type does not derive one from another or guarantee they agree.
 *
 * @param activeProjectId the project in focus, empty when no project is open
 * @param activeDocumentId the document in focus, empty when none is open
 * @param activeModelId the model in focus, empty when none is open
 * @param selectedParameterIds selected parameters; unmodifiable
 * @param selectedArtMeshIds selected art meshes; unmodifiable
 * @param selectedDeformerIds selected deformers; unmodifiable
 * @param selectedModelObjectIds selected model objects irrespective of kind; unmodifiable
 */
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

    /**
     * @return the canonical "nothing selected, nothing open" summary - every optional empty and
     *         every list empty. Use this instead of {@code null} to represent absence of selection.
     */
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
