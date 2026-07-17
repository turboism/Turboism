package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SelectionQueryServiceImpl implements SelectionQueryService {

    public static final String SELECTION_READ_CAPABILITY = "cubism.selection.read";
    public static final String CURRENT_SELECTION_OPERATION = "selectionQuery.currentSelection";
    public static final String SELECTED_IDS_OPERATION = "selectionQuery.selectedIds";
    public static final String SELECTION_CHANGED_OPERATION = "selectionQuery.onSelectionChanged";

    private static final String SELECTION_TASK_TYPE = "event.subscribe";
    private static final String DEFAULT_CAPABILITY = "none";

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private final RuntimeScheduler scheduler;
    private final CopyOnWriteArrayList<SelectionSubscription> subscriptions = new CopyOnWriteArrayList<>();

    public SelectionQueryServiceImpl(
        final CubismFacadeImpl facade,
        final CubismPermissionGate permissionGate,
        final RuntimeScheduler scheduler
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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

    @Override
    public Registration onSelectionChanged(final SelectionChangedListener listener) throws CubismServiceException {
        Objects.requireNonNull(listener, "listener");
        requireModelRead(SELECTION_CHANGED_OPERATION);
        final SelectionSubscription subscription = new SelectionSubscription(
            listener,
            selectionSummary(runtimeWithServiceError().snapshot())
        );
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
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
        for (SelectionSubscription subscription : subscriptions) {
            subscription.publishIfChanged(currentSelection);
        }
    }

    private final class SelectionSubscription {
        private final SelectionChangedListener listener;
        private final AtomicReference<PendingSelectionChange> pendingChange = new AtomicReference<>();
        private final AtomicBoolean dispatchScheduled = new AtomicBoolean();
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
            pendingChange.set(new PendingSelectionChange(eventPreviousSelection, currentSelection));
            scheduleDispatch(currentSelection);
        }

        private void dispatchLatestSelectionChange() {
            final PendingSelectionChange change = pendingChange.getAndSet(null);
            try {
                if (change != null) {
                    listener.selectionChanged(new CubismSelectionChangedEvent(change.previousSelection(), change.currentSelection()));
                }
            } finally {
                dispatchScheduled.set(false);
                final PendingSelectionChange pending = pendingChange.get();
                if (pending != null) {
                    scheduleDispatch(pending.currentSelection());
                }
            }
        }

        private void scheduleDispatch(final SelectionSummary selection) {
            if (dispatchScheduled.compareAndSet(false, true)) {
                scheduler.dispatch(selectionTask(selection), this::dispatchLatestSelectionChange);
            }
        }
    }

    private static PluginTask selectionTask(final SelectionSummary selection) {
        return new PluginTask(
            SELECTION_TASK_TYPE,
            "turboism.selection-query",
            "selection changed: " + selection.selectedModelObjectIds().size() + " selected object(s)",
            DEFAULT_CAPABILITY
        );
    }

    private record PendingSelectionChange(SelectionSummary previousSelection, SelectionSummary currentSelection) {
    }
}
