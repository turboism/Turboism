package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelHierarchyQueryServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void currentHierarchyFindNodeAndChildrenOfReturnImmutableHierarchyWhenPermissionGranted() throws CubismServiceException {
        final VersionedHierarchySource source = VersionedHierarchySource.withModel();
        final ModelHierarchyQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
            permission(CubismFacadeImpl.MESH_READ_PERMISSION)
        ));

        final ModelHierarchy hierarchy = service.currentHierarchy().orElseThrow();
        final Optional<HierarchyNode> deformer = service.findNode(new ModelObjectId("deformer-root"));
        final List<HierarchyNode> deformerChildren = service.childrenOf(new ModelObjectId("deformer-root"));

        assertEquals(new ModelObjectId("model-1"), hierarchy.rootNode().id());
        assertEquals(List.of(new ModelObjectId("param-angle-x"), new ModelObjectId("deformer-root")), hierarchy.rootNode().childIds());
        assertTrue(deformer.isPresent());
        assertEquals(HierarchyNode.Kind.DEFORMER, deformer.orElseThrow().kind());
        assertEquals(List.of(new ModelObjectId("mesh-face")), deformerChildren.stream().map(HierarchyNode::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> hierarchy.nodes().add(deformer.orElseThrow()));
    }

    @Test
    void currentHierarchyUsesCachedHierarchyUntilSnapshotVersionChanges() throws CubismServiceException {
        final VersionedHierarchySource source = VersionedHierarchySource.withModel();
        final ModelHierarchyQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final ModelHierarchy first = service.currentHierarchy().orElseThrow();
        source.replaceDeformersWithoutInvalidation(List.of(new HostSnapshotSource.HostDeformer("deformer-new", "New", DeformerType.WARP, Optional.empty(), List.of())));
        final ModelHierarchy cached = service.currentHierarchy().orElseThrow();
        source.advanceInvalidationToken();
        final ModelHierarchy refreshed = service.currentHierarchy().orElseThrow();

        assertSame(first, cached);
        assertTrue(refreshed.findNode(new ModelObjectId("deformer-new")).isPresent());
    }

    @Test
    void noActiveModelReturnsEmptyHierarchyAndEmptyLookups() throws CubismServiceException {
        final ModelHierarchyQueryServiceImpl service = serviceWith(VersionedHierarchySource.withoutModel(), new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        assertTrue(service.currentHierarchy().isEmpty());
        assertTrue(service.findNode(new ModelObjectId("missing")).isEmpty());
        assertTrue(service.childrenOf(new ModelObjectId("missing")).isEmpty());
    }

    @Test
    void everyDeniedModelReadOperationRecordsItsOperationAndModelTreeCapability() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final ModelHierarchyQueryServiceImpl service = serviceWith(
            VersionedHierarchySource.withModel(),
            auditEvents,
            List.of()
        );

        assertDenied(service::currentHierarchy, auditEvents, ModelHierarchyQueryServiceImpl.CURRENT_HIERARCHY_OPERATION);
        assertDenied(
            () -> service.childrenOf(new ModelObjectId("model-1")),
            auditEvents,
            ModelHierarchyQueryServiceImpl.CHILDREN_OF_OPERATION
        );
        assertDenied(
            () -> service.findNode(new ModelObjectId("model-1")),
            auditEvents,
            ModelHierarchyQueryServiceImpl.FIND_NODE_OPERATION
        );
    }

    private static void assertDenied(
        final ThrowingHierarchyOperation operation,
        final List<CubismFacadeAuditEvent> auditEvents,
        final String expectedOperationId
    ) {
        final CubismPermissionException error = assertThrows(CubismPermissionException.class, operation::run);

        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        final CubismFacadeAuditEvent event = auditEvents.remove(0);
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, event.permissionId());
        assertEquals(expectedOperationId, event.operationId());
        assertEquals(ModelHierarchyQueryServiceImpl.MODEL_TREE_READ_CAPABILITY, event.capabilityId());
        assertEquals(FIXED_CLOCK.instant(), event.timestamp());
    }

    @FunctionalInterface
    private interface ThrowingHierarchyOperation {
        void run() throws CubismServiceException;
    }

    @Test
    void deniedMeshReadFiltersArtMeshNodesFromHierarchy() throws CubismServiceException {
        final VersionedHierarchySource source = VersionedHierarchySource.withModel();
        final ModelHierarchyQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final ModelHierarchy hierarchy = service.currentHierarchy().orElseThrow();

        assertEquals(List.of(new ModelObjectId("param-angle-x"), new ModelObjectId("deformer-root")), hierarchy.rootNode().childIds());
        assertTrue(service.findNode(new ModelObjectId("mesh-face")).isEmpty());
        assertTrue(service.childrenOf(new ModelObjectId("deformer-root")).isEmpty());
    }

    private static ModelHierarchyQueryServiceImpl serviceWith(
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
        return new ModelHierarchyQueryServiceImpl(new CubismFacadeImpl(source, permissionGate), permissionGate);
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

    private static final class VersionedHierarchySource implements HostSnapshotSource {

        private static final HostParameter PARAMETER = new HostParameter("param-angle-x", "Angle X", 0.0, 0.0, -30.0, 30.0, true, true);
        private static final HostArtMesh MESH = new HostArtMesh("mesh-face", "Face Mesh", Optional.empty(), true, true);

        private final boolean hasModel;
        private List<HostDeformer> deformers;
        private long invalidationToken;

        private VersionedHierarchySource(final boolean hasModel, final List<HostDeformer> deformers) {
            this.hasModel = hasModel;
            this.deformers = List.copyOf(deformers);
        }

        static VersionedHierarchySource withModel() {
            return new VersionedHierarchySource(true, List.of(new HostDeformer(
                "deformer-root",
                "Root",
                DeformerType.ROOT,
                Optional.empty(),
                List.of("mesh-face")
            )));
        }

        static VersionedHierarchySource withoutModel() {
            return new VersionedHierarchySource(false, List.of());
        }

        void replaceDeformersWithoutInvalidation(final List<HostDeformer> nextDeformers) {
            deformers = List.copyOf(nextDeformers);
        }

        void advanceInvalidationToken() {
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
            if (!hasModel) {
                return Optional.empty();
            }
            return Optional.of(new HostModel("model-1", "Model", List.of(PARAMETER), List.of(MESH), deformers));
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
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
