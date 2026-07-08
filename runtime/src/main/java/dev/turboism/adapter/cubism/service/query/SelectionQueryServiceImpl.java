package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
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
import dev.turboism.sdk.event.cubism.CubismSelectionChangedEvent;
import dev.turboism.sdk.plugin.Registration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SelectionQueryServiceImpl implements SelectionQueryService {

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private final CopyOnWriteArrayList<SelectionSubscription> subscriptions = new CopyOnWriteArrayList<>();

    public SelectionQueryServiceImpl(final CubismFacadeImpl facade, final CubismPermissionGate permissionGate) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
    }

    @Override
    public SelectionSummary currentSelection() throws CubismServiceException {
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "selectionQuery.currentSelection");
        final SelectionSummary summary = selectionSummary(runtimeWithServiceError().snapshot());
        publishSelectionChanges(summary);
        return summary;
    }

    @Override
    public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) throws CubismServiceException {
        Objects.requireNonNull(kind, "kind");
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "selectionQuery.selectedIds");
        final SelectionSummary summary = selectionSummary(runtimeWithServiceError().snapshot());
        publishSelectionChanges(summary);
        return switch (kind) {
            case MODEL, GROUP, PART, UNKNOWN -> summary.selectedModelObjectIds();
            case PARAMETER -> summary.selectedParameterIds().stream().map(id -> new ModelObjectId(id.value())).toList();
            case ART_MESH -> summary.selectedArtMeshIds().stream().map(id -> new ModelObjectId(id.value())).toList();
            case DEFORMER -> summary.selectedDeformerIds().stream().map(id -> new ModelObjectId(id.value())).toList();
        };
    }

    @Override
    public Registration onSelectionChanged(final SelectionChangedListener listener) throws CubismServiceException {
        Objects.requireNonNull(listener, "listener");
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "selectionQuery.onSelectionChanged");
        final SelectionSubscription subscription = new SelectionSubscription(
            listener,
            selectionSummary(runtimeWithServiceError().snapshot())
        );
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
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
        for (SelectionSubscription subscription : subscriptions) {
            subscription.publishIfChanged(currentSelection);
        }
    }

    private static final class SelectionSubscription {
        private final SelectionChangedListener listener;
        private SelectionSummary previousSelection;

        private SelectionSubscription(final SelectionChangedListener listener, final SelectionSummary previousSelection) {
            this.listener = listener;
            this.previousSelection = previousSelection;
        }

        private void publishIfChanged(final SelectionSummary currentSelection) {
            if (previousSelection.equals(currentSelection)) {
                return;
            }
            final SelectionSummary eventPreviousSelection = previousSelection;
            previousSelection = currentSelection;
            listener.selectionChanged(new CubismSelectionChangedEvent(eventPreviousSelection, currentSelection));
        }
    }
}
