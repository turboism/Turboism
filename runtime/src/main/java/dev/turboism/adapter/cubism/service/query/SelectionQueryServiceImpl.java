package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.SelectionObservation;
import dev.turboism.adapter.cubism.SelectionSummaries;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime implementation of the selection query service.
 *
 * <p>Every entry point requires the model-read permission. Selection is derived on each call from
 * the current snapshot rather than cached — the host's raw selected-object ids are classified into
 * parameters, art meshes and deformers by looking each one up in the snapshot, and any id that
 * matches none of those remains only in the generic model-object list.
 *
 * <p>Two producers share the runtime-owned observation baseline: fresh queries (this class) and
 * the session-scoped {@link dev.turboism.adapter.cubism.SelectionObservationPublisher}, which
 * observes the host's raw selection while {@link SelectionChangedEvent} subscriptions exist.
 * Both commit through {@link SelectionObservation#commit} on
 * {@link SelectionSummaries#observedIdentity} — the project-less identity — so identical host
 * state observed through either path never emits duplicate events, a strictly older same-source
 * revision cannot regress a newer committed baseline, and event payloads never carry the
 * permission-gated project id.
 */
public final class SelectionQueryServiceImpl implements SelectionQueryService {

    public static final String SELECTION_READ_CAPABILITY = "cubism.selection.read";
    public static final String CURRENT_SELECTION_OPERATION = "selectionQuery.currentSelection";
    public static final String SELECTED_IDS_OPERATION = "selectionQuery.selectedIds";

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private final RuntimeEventBroker eventBroker;
    private final AtomicReference<SelectionObservation> observedSelection;
    private final HostSnapshotSource observationSource;

    public SelectionQueryServiceImpl(
        final CubismFacadeImpl facade,
        final CubismPermissionGate permissionGate,
        final RuntimeEventBroker eventBroker,
        final AtomicReference<SelectionObservation> observedSelection,
        final HostSnapshotSource observationSource
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.observedSelection = Objects.requireNonNull(observedSelection, "observedSelection");
        // The identity tag commits under: production shares one session source
        // instance across query facades and the observer, so versions compare.
        this.observationSource = Objects.requireNonNull(observationSource, "observationSource");
    }

    @Override
    public SelectionSummary currentSelection() throws CubismServiceException {
        requireModelRead(CURRENT_SELECTION_OPERATION);
        final SnapshotWithVersion versioned = runtimeWithServiceError();
        final SelectionSummary summary =
            SelectionSummaries.fromRuntimeSnapshot(versioned.snapshot());
        publishSelectionChanges(versioned, summary);
        return summary;
    }

    @Override
    public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) throws CubismServiceException {
        Objects.requireNonNull(kind, "kind");
        requireModelRead(SELECTED_IDS_OPERATION);
        final SnapshotWithVersion versioned = runtimeWithServiceError();
        final SelectionSummary summary =
            SelectionSummaries.fromRuntimeSnapshot(versioned.snapshot());
        publishSelectionChanges(versioned, summary);
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

    private void publishSelectionChanges(
        final SnapshotWithVersion versioned,
        final SelectionSummary currentSelection
    ) {
        final SelectionSummary identity =
            SelectionSummaries.observedIdentity(currentSelection);
        SelectionObservation.commit(
            observedSelection,
            new SelectionObservation(observationSource, versioned.version(), identity),
            (previous, current) -> eventBroker.publishRuntime(
                new SelectionChangedEvent(previous, current)
            )
        );
    }
}
