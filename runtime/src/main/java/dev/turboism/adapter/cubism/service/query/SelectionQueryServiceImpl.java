package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime implementation of the selection query service.
 *
 * <p>Every entry point requires the model-read permission. Selection is derived on each call from
 * the current snapshot rather than cached — the host's raw selected-object ids are classified into
 * parameters, art meshes and deformers by looking each one up in the snapshot, and any id that
 * matches none of those remains only in the generic model-object list.
 *
 * <p>There is no background polling. A Runtime-owned {@link SelectionChangedEvent} is published
 * only when a fresh query detects that the detached selection summary changed. The atomic baseline
 * preserves one global observation sequence across plugin-scoped query service instances.
 */
public final class SelectionQueryServiceImpl implements SelectionQueryService {

    public static final String SELECTION_READ_CAPABILITY = "cubism.selection.read";
    public static final String CURRENT_SELECTION_OPERATION = "selectionQuery.currentSelection";
    public static final String SELECTED_IDS_OPERATION = "selectionQuery.selectedIds";

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private final RuntimeEventBroker eventBroker;
    private final AtomicReference<SelectionSummary> observedSelection;

    public SelectionQueryServiceImpl(
        final CubismFacadeImpl facade,
        final CubismPermissionGate permissionGate,
        final RuntimeEventBroker eventBroker,
        final AtomicReference<SelectionSummary> observedSelection
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.observedSelection = Objects.requireNonNull(observedSelection, "observedSelection");
    }

    @Override
    public SelectionSummary currentSelection() throws CubismServiceException {
        requireModelRead(CURRENT_SELECTION_OPERATION);
        final SelectionSummary summary = selectionSummary(runtimeWithServiceError().snapshot());
        publishSelectionChanges(summary);
        return summary;
    }

    @Override
    public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) throws CubismServiceException {
        Objects.requireNonNull(kind, "kind");
        requireModelRead(SELECTED_IDS_OPERATION);
        final SelectionSummary summary = selectionSummary(runtimeWithServiceError().snapshot());
        publishSelectionChanges(summary);
        return switch (kind) {
            case MODEL, GROUP, PART, UNKNOWN -> summary.selectedModelObjectIds();
            case PARAMETER -> summary.selectedParameterIds().stream().map(id -> new ModelObjectId(id.value())).toList();
            case ART_MESH -> summary.selectedArtMeshIds().stream().map(id -> new ModelObjectId(id.value())).toList();
            case DEFORMER -> summary.selectedDeformerIds().stream().map(id -> new ModelObjectId(id.value())).toList();
        };
    }

    private void requireModelRead(final String operationId) {
        permissionGate.require(
            CubismFacadeImpl.MODEL_READ_PERMISSION,
            operationId,
            SELECTION_READ_CAPABILITY
        );
    }

    private SnapshotWithVersion runtimeWithServiceError() throws CubismServiceException {
        try {
            return facade.runtimeWithVersion();
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new CubismServiceException(ServiceError.INVALID_SNAPSHOT, "Cubism runtime snapshot is invalid.", error);
        }
    }

    private SelectionSummary selectionSummary(final CubismRuntimeSnapshot snapshot) {
        final SelectionSnapshot selection = snapshot.selection();
        final Set<String> parameterIds = new HashSet<>(snapshot.parameters().stream().map(ParameterSnapshot::id).toList());
        final Set<String> artMeshIds = new HashSet<>(snapshot.artMeshes().stream().map(ArtMeshSnapshot::id).toList());
        final Set<String> deformerIds = new HashSet<>(snapshot.deformers().stream().map(DeformerSnapshot::id).toList());
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

    private void publishSelectionChanges(final SelectionSummary currentSelection) {
        final SelectionSummary previous = observedSelection.getAndSet(currentSelection);
        if (previous != null && !previous.equals(currentSelection)) {
            eventBroker.publishRuntime(new SelectionChangedEvent(previous, currentSelection));
        }
    }
}
