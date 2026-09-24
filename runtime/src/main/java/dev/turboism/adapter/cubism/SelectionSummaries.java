package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared {@link SelectionSummary} derivation used by both the query service and the
 * runtime-owned selection observer.
 *
 * <p>Selection is derived from the current snapshot: the host's raw selected-object ids are
 * classified into parameters, art meshes and deformers by looking each one up in the snapshot,
 * and any id that matches none of those remains only in the generic model-object list.</p>
 */
public final class SelectionSummaries {

    private SelectionSummaries() { }

    /**
     * Classifies one runtime snapshot into a detached selection summary. The summary keeps
     * {@code activeProjectId} only when the snapshot carries it — the project portion is
     * permission-gated upstream in the query path and intentionally absent from runtime
     * observation.
     */
    public static SelectionSummary fromRuntimeSnapshot(final CubismRuntimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final SelectionSnapshot selection = snapshot.selection();
        final Set<String> parameterIds = new HashSet<>(
            snapshot.parameters().stream().map(ParameterSnapshot::id).toList()
        );
        final Set<String> artMeshIds = new HashSet<>(
            snapshot.artMeshes().stream().map(ArtMeshSnapshot::id).toList()
        );
        final Set<String> deformerIds = new HashSet<>(
            snapshot.deformers().stream().map(DeformerSnapshot::id).toList()
        );
        final List<ParameterId> selectedParameterIds = new ArrayList<>();
        final List<ArtMeshId> selectedArtMeshIds = new ArrayList<>();
        final List<DeformerId> selectedDeformerIds = new ArrayList<>();
        final List<ModelObjectId> selectedModelObjectIds = new ArrayList<>();
        for (String selectedObjectId : selection.selectedObjectIds()) {
            selectedModelObjectIds.add(new ModelObjectId(selectedObjectId));
            if (parameterIds.contains(selectedObjectId)) {
                selectedParameterIds.add(new ParameterId(selectedObjectId));
            } else if (artMeshIds.contains(selectedObjectId)) {
                selectedArtMeshIds.add(new ArtMeshId(selectedObjectId));
            } else if (deformerIds.contains(selectedObjectId)) {
                selectedDeformerIds.add(new DeformerId(selectedObjectId));
            }
        }
        return new SelectionSummary(
            snapshot.project().map(project -> new ProjectId(project.projectId())),
            snapshot.document().map(document -> new DocumentId(document.documentId())),
            snapshot.model().map(model -> new ModelObjectId(model.modelId())),
            selectedParameterIds,
            selectedArtMeshIds,
            selectedDeformerIds,
            selectedModelObjectIds
        );
    }

    /**
     * The deduplication and event-payload form of a summary: identical to the source except
     * that {@code activeProjectId} is stripped. Project identity is permission-gated per plugin
     * in the query path, so it cannot participate in a runtime-global observation baseline —
     * including it would make query-driven and observer-driven summaries flap against each
     * other and leak the project id to subscribers without the project-read permission.
     */
    public static SelectionSummary observedIdentity(final SelectionSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new SelectionSummary(
            Optional.empty(),
            summary.activeDocumentId(),
            summary.activeModelId(),
            summary.selectedParameterIds(),
            summary.selectedArtMeshIds(),
            summary.selectedDeformerIds(),
            summary.selectedModelObjectIds()
        );
    }
}
