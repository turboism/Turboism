package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.event.cubism.CubismSelectionChangedEvent;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionQueryServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void currentSelectionAndSelectedIdsReturnTypedModelObjectIdsWhenPermissionGranted() throws CubismServiceException {
        final MutableSelectionSource source = MutableSelectionSource.withSelection(List.of("param-angle-x", "mesh-face"));
        final SelectionQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final SelectionSummary selection = service.currentSelection();
        final List<ModelObjectId> selectedMeshes = service.selectedIds(HierarchyNode.Kind.ART_MESH);
        final List<ModelObjectId> selectedParameters = service.selectedIds(HierarchyNode.Kind.PARAMETER);

        assertEquals(List.of(new ModelObjectId("param-angle-x"), new ModelObjectId("mesh-face")), selection.selectedModelObjectIds());
        assertEquals(List.of(new ModelObjectId("mesh-face")), selectedMeshes);
        assertEquals(List.of(new ModelObjectId("param-angle-x")), selectedParameters);
    }

    @Test
    void onSelectionChangedEmitsOnlyWhenSelectionDiffersAndStopsAfterClose() throws CubismServiceException {
        final MutableSelectionSource source = MutableSelectionSource.withSelection(List.of("param-angle-x"));
        final SelectionQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));
        final List<CubismSelectionChangedEvent> events = new ArrayList<>();

        final Registration registration = service.onSelectionChanged(events::add);
        service.currentSelection();
        source.replaceSelection(List.of("mesh-face"));
        service.currentSelection();
        source.replaceSelection(List.of("mesh-face"));
        service.currentSelection();
        registration.close();
        source.replaceSelection(List.of("deformer-root"));
        service.currentSelection();

        assertEquals(1, events.size());
        assertEquals(List.of(new ModelObjectId("param-angle-x")), events.get(0).previousSelection().selectedModelObjectIds());
        assertEquals(List.of(new ModelObjectId("mesh-face")), events.get(0).currentSelection().selectedModelObjectIds());
    }

    @Test
    void deniedModelReadThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final SelectionQueryServiceImpl service = serviceWith(MutableSelectionSource.withSelection(List.of("mesh-face")), auditEvents, List.of());

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, service::currentSelection);

        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("selectionQuery.currentSelection", auditEvents.get(0).methodName());
        assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
    }

    private static SelectionQueryServiceImpl serviceWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions
    ) {
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        );
        return new SelectionQueryServiceImpl(new CubismFacadeImpl(source, permissionGate), permissionGate, directScheduler());
    }

    private static RuntimeScheduler directScheduler() {
        return new RuntimeScheduler(
            task -> WorkBudget.SIDECAR,
            new PluginExecutorRegistry(1, 2, event -> { }, FIXED_CLOCK),
            (task, callback) -> {
                callback.run();
                return CompletableFuture.completedFuture(dev.turboism.core.runtime.sidecar.SidecarResult.success(""));
            },
            event -> { }
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private static final class MutableSelectionSource implements HostSnapshotSource {

        private static final HostParameter PARAMETER = new HostParameter("param-angle-x", "Angle X", 0.0, 0.0, -30.0, 30.0, true, true);
        private static final HostArtMesh MESH = new HostArtMesh("mesh-face", "Face Mesh", Optional.empty(), true, true);
        private static final HostDeformer DEFORMER = new HostDeformer("deformer-root", "Root", DeformerType.ROOT, Optional.empty(), List.of("mesh-face"));
        private static final HostModel MODEL = new HostModel("model-1", "Model", List.of(PARAMETER), List.of(MESH), List.of(DEFORMER));

        private List<String> selectedObjectIds;
        private long invalidationToken;

        private MutableSelectionSource(final List<String> selectedObjectIds) {
            this.selectedObjectIds = List.copyOf(selectedObjectIds);
        }

        static MutableSelectionSource withSelection(final List<String> selectedObjectIds) {
            return new MutableSelectionSource(selectedObjectIds);
        }

        void replaceSelection(final List<String> nextSelectedObjectIds) {
            selectedObjectIds = List.copyOf(nextSelectedObjectIds);
            invalidationToken++;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.of(MODEL);
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(selectedObjectIds, Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            return invalidationToken;
        }
    }
}
