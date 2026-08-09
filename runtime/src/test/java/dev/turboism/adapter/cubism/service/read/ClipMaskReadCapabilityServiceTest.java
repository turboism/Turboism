package dev.turboism.adapter.cubism.service.read;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskReadCapabilityServiceTest {

    @Test
    void availableEmptyDirectAdapterDoesNotFallBack() {
        AtomicInteger fallbackReads = new AtomicInteger();
        CubismReadCapabilityServiceImpl service = service(
            ClipMaskReadAdapter.Impl.connected(new FixedClipHost(List.of())),
            fallback(fallbackReads)
        );

        assertEquals(List.of(), service.clipMasks());
        assertEquals(0, fallbackReads.get());
        assertTrue(service.clipMaskDiagnostics().isEmpty());
    }

    @Test
    void unavailableDirectAdapterRecordsDiagnosticAndFallsBack() {
        AtomicInteger fallbackReads = new AtomicInteger();
        CubismReadCapabilityServiceImpl service = service(
            ClipMaskReadAdapter.Impl.safeMode(),
            fallback(fallbackReads)
        );

        assertEquals("fallback-target", service.clipMasks().get(0).targetMeshId());
        assertEquals(1, fallbackReads.get());
        assertEquals(
            SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
            service.clipMaskDiagnostics().get(0).code()
        );
    }

    @Test
    void deniedModelReadOperationsAuditRealIdsBeforeReadingTheFacade() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismPermissionGate gate = gate(List.of(), auditEvents);
        final CubismReadCapabilityServiceImpl service = new CubismReadCapabilityServiceImpl(
            new CubismFacadeImpl(modelSource(), gate),
            M12ReadSnapshotSource.EMPTY,
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode(),
            "plugin.read.test",
            CubismReadPermissionGate.from(gate)
        );

        assertDenied(service::selection, auditEvents, "cubismRead.selection", "cubism.selection.read");
        assertDenied(service::modelObjects, auditEvents, "cubismRead.modelObjects", "cubism.model-tree.read");
        assertDenied(service::activeDocument, auditEvents, "activeDocument", null);
        assertDenied(service::activeModel, auditEvents, "activeModel", null);

        final CubismPermissionException projectError = assertThrows(
            CubismPermissionException.class, service::activeProject
        );
        assertTrue(projectError.getMessage().contains(CubismFacadeImpl.PROJECT_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        final CubismFacadeAuditEvent projectEvent = auditEvents.remove(0);
        assertEquals(CubismFacadeImpl.PROJECT_READ_PERMISSION, projectEvent.permissionId());
        assertEquals("activeProject", projectEvent.operationId());
        assertNull(projectEvent.capabilityId());
    }

    @Test
    void everySupplementalReadOperationUsesTheInjectedCapabilityAwareGateBeforeReading() {
        final List<String> calls = new ArrayList<>();
        final CubismReadCapabilityServiceImpl service = new CubismReadCapabilityServiceImpl(
            new UncheckedFacade(),
            M12ReadSnapshotSource.EMPTY,
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode(),
            "plugin.read.test",
            (permissionId, operationId, capabilityId) -> {
                calls.add(permissionId + "|" + operationId + "|" + capabilityId);
                throw new CubismPermissionException("denied " + operationId);
            }
        );

        final List<ReadOperation> operations = List.of(
            new ReadOperation(service::selection, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.selection", "cubism.selection.read"),
            new ReadOperation(service::parameters, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.parameters", "cubism.parameter.read"),
            new ReadOperation(service::modelObjects, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.modelObjects", "cubism.model-tree.read"),
            new ReadOperation(service::meshes, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.meshes", "cubism.mesh.read"),
            new ReadOperation(service::deformers, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.deformers", "cubism.deformer.read"),
            new ReadOperation(service::psdDocuments, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.psdDocuments", "cubism.psd.read"),
            new ReadOperation(service::clipMasks, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.clipMasks", "cubism.clipmask.read"),
            new ReadOperation(service::textureAtlases, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.textureAtlases", "cubism.texture-atlas.read"),
            new ReadOperation(service::renderStatus, CubismFacadeImpl.MODEL_READ_PERMISSION, "cubismRead.renderStatus", "cubism.render.status.read"),
            new ReadOperation(service::workspace, CubismFacadeImpl.PROJECT_READ_PERMISSION, "cubismRead.workspace", "cubism.workspace.read"),
            new ReadOperation(service::themeStatus, CubismFacadeImpl.PROJECT_READ_PERMISSION, "cubismRead.themeStatus", "cubism.theme.status.read")
        );

        for (ReadOperation operation : operations) {
            assertThrows(CubismPermissionException.class, operation.call()::get);
            assertEquals(
                operation.permissionId() + "|" + operation.operationId() + "|" + operation.capabilityId(),
                calls.remove(0)
            );
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void overlappingActiveReadsFollowFacadeAndIgnoreWorkspaceAdapterAnswers() {
        assertActiveReadsMatch(modelProjection());
        assertActiveReadsMatch(animationProjection());
        assertActiveReadsMatch(imageProjection());
    }

    private static void assertActiveReadsMatch(final ProjectionFacade facade) {
        final CubismReadCapabilityServiceImpl service = new CubismReadCapabilityServiceImpl(
            facade,
            M12ReadSnapshotSource.EMPTY,
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            divergentWorkspace(),
            ClipMaskReadAdapter.Impl.safeMode(),
            "plugin.overlap.test",
            (permissionId, operationId, capabilityId) -> {
                throw new AssertionError("facade-owned active read used the supplemental gate");
            }
        );

        assertEquals(facade.activeProject(), service.activeProject());
        assertEquals(facade.activeDocument(), service.activeDocument());
        assertEquals(facade.activeModel(), service.activeModel());
        assertEquals(facade.activeAnimation(), service.activeAnimation());
        assertEquals(facade.activeImageDocument(), service.activeImageDocument());
        assertEquals(facade.activeProjectContent(), service.activeProjectContent());
    }

    private static ProjectionFacade modelProjection() {
        final ModelSnapshot model = new ModelSnapshot("facade-model", "Facade Model", List.of(), List.of(), List.of(), List.of());
        final DocumentSnapshot document = new DocumentSnapshot(
            "facade-model-document", "Facade Model", "models/facade.cmo3", Optional.empty(),
            Optional.of(model), DocumentKind.MODEL, Optional.of("facade-model-content"), Optional.empty()
        );
        final ProjectContentSnapshot content = new ProjectContentSnapshot(
            "facade-model-content", "Facade Model", ProjectContentKind.MODEL, Optional.empty(),
            List.of(document.documentId())
        );
        final ProjectSnapshot project = new ProjectSnapshot(
            "facade-project", "Facade Project", Optional.empty(), List.of(document), List.of(content)
        );
        return new ProjectionFacade(Optional.of(project), Optional.of(document), Optional.of(model));
    }

    private static ProjectionFacade animationProjection() {
        final AnimationSnapshot animation = new AnimationSnapshot(
            "facade-animation", "Facade Animation", Optional.empty(),
            List.of("facade-animation-document"), Optional.of("facade-animation-document")
        );
        final DocumentSnapshot document = new DocumentSnapshot(
            "facade-animation-document", "Facade Animation", "animations/facade.can3", Optional.empty(),
            Optional.empty(), DocumentKind.ANIMATION_SCENE, Optional.empty(), Optional.of(animation)
        );
        return new ProjectionFacade(Optional.empty(), Optional.of(document), Optional.empty());
    }

    private static ProjectionFacade imageProjection() {
        final DocumentSnapshot document = new DocumentSnapshot(
            "facade-image-document", "Facade Image", "images/facade.psd", Optional.empty(),
            Optional.empty(), DocumentKind.IMAGE, Optional.of("facade-image-content"), Optional.empty()
        );
        final ProjectContentSnapshot content = new ProjectContentSnapshot(
            "facade-image-content", "Facade Image", ProjectContentKind.IMAGE, Optional.empty(),
            List.of(document.documentId())
        );
        final ProjectSnapshot project = new ProjectSnapshot(
            "facade-image-project", "Facade Image Project", Optional.empty(), List.of(document), List.of(content)
        );
        return new ProjectionFacade(Optional.of(project), Optional.of(document), Optional.empty());
    }

    private static ProjectWorkspaceAdapter divergentWorkspace() {
        return ProjectWorkspaceAdapter.Impl.connected(new ProjectWorkspaceAdapter.HostOperations() {
            @Override public String hostVersion() { return "5.3.02"; }
            @Override public boolean supportsProjectWorkspaceRead() { return true; }
            @Override public Optional<ProjectSnapshot> activeProject() {
                return Optional.of(new ProjectSnapshot("adapter-project", "Adapter Project", Optional.empty(), List.of()));
            }
            @Override public Optional<DocumentSnapshot> activeDocument() {
                return Optional.of(new DocumentSnapshot("adapter-document", "Adapter Document", "adapter.cmo3", Optional.empty(), Optional.empty()));
            }
            @Override public Optional<WorkspaceSnapshot> workspace() {
                return Optional.of(new WorkspaceSnapshot("adapter-workspace", "Adapter Workspace", List.of()));
            }
        });
    }


    @Test
    void deniedClipMaskReadAuditsRealIdsBeforeTouchingAdapterOrFallback() {
        final AtomicInteger adapterReads = new AtomicInteger();
        final AtomicInteger fallbackReads = new AtomicInteger();
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismPermissionGate gate = gate(List.of(), auditEvents);
        final CubismReadCapabilityServiceImpl service = new CubismReadCapabilityServiceImpl(
            new CubismFacadeImpl(emptySource(), gate),
            fallback(fallbackReads),
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.connected(new ClipMaskReadAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsClipMaskRead() { return true; }
                @Override public List<ClipMaskSnapshot> clipMasks() {
                    adapterReads.incrementAndGet();
                    return List.of();
                }
            }),
            "plugin.clipmask.test",
            CubismReadPermissionGate.from(gate)
        );

        assertThrows(CubismPermissionException.class, service::clipMasks);

        assertEquals(0, adapterReads.get());
        assertEquals(0, fallbackReads.get());
        final CubismFacadeAuditEvent event = auditEvents.get(0);
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, event.permissionId());
        assertEquals("cubismRead.clipMasks", event.operationId());
        assertEquals("cubism.clipmask.read", event.capabilityId());
    }

    private static void assertDenied(
        final Supplier<?> operation,
        final List<CubismFacadeAuditEvent> auditEvents,
        final String expectedOperationId,
        final String expectedCapabilityId
    ) {
        final CubismPermissionException error = assertThrows(CubismPermissionException.class, operation::get);

        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        final CubismFacadeAuditEvent event = auditEvents.remove(0);
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, event.permissionId());
        assertEquals(expectedOperationId, event.operationId());
        assertEquals(expectedCapabilityId, event.capabilityId());
    }

    private static CubismReadCapabilityServiceImpl service(
        final ClipMaskReadAdapter adapter,
        final M12ReadSnapshotSource fallback
    ) {
        final CubismPermissionGate gate = modelReadGate();
        return new CubismReadCapabilityServiceImpl(
            new CubismFacadeImpl(emptySource(), gate),
            fallback,
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            adapter,
            "plugin.clipmask.test",
            CubismReadPermissionGate.from(gate)
        );
    }

    private record ReadOperation(
        Supplier<?> call,
        String permissionId,
        String operationId,
        String capabilityId
    ) {
    }

    private record ProjectionFacade(
        Optional<ProjectSnapshot> project,
        Optional<DocumentSnapshot> document,
        Optional<ModelSnapshot> modelSnapshot
    ) implements CubismFacade {
        @Override public Optional<ProjectSnapshot> activeProject() { return project; }
        @Override public Optional<DocumentSnapshot> activeDocument() { return document; }
        @Override public Optional<ModelSnapshot> activeModel() { return modelSnapshot; }
        @Override public CubismRuntimeSnapshot runtime() { throw new AssertionError("runtime must not be read"); }
        @Override public boolean isHostPresent() { return true; }
        @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UncheckedFacade implements CubismFacade {
        @Override public Optional<ProjectSnapshot> activeProject() { throw new AssertionError("facade must not be read"); }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw new AssertionError("facade must not be read"); }
        @Override public Optional<ModelSnapshot> activeModel() { throw new AssertionError("facade must not be read"); }
        @Override public CubismRuntimeSnapshot runtime() { throw new AssertionError("facade must not be read"); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
            throw new UnsupportedOperationException();
        }
    }

    private static HostSnapshotSource modelSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() {
                return Optional.of(new HostDocument(
                    "document-1",
                    "Document",
                    "models/demo/model.cdi3.json",
                    Optional.empty(),
                    Optional.of(model())
                ));
            }
            @Override public Optional<HostModel> activeModel() { return Optional.of(model()); }
            @Override public HostSelection selection() {
                return new HostSelection(List.of("model-1"), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return true; }
            @Override public long invalidationToken() { return 0L; }

            private HostModel model() {
                return new HostModel("model-1", "Model", List.of(), List.of(), List.of());
            }
        };
    }

    private static HostSnapshotSource emptySource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0L; }
        };
    }

    private static M12ReadSnapshotSource fallback(final AtomicInteger reads) {
        return new M12ReadSnapshotSource() {
            @Override
            public List<ClipMaskSnapshot> clipMasks() {
                reads.incrementAndGet();
                return List.of(new ClipMaskSnapshot("fallback-target", List.of("fallback-source"), false));
            }
        };
    }

    private static CubismPermissionGate modelReadGate() {
        return gate(List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)), new ArrayList<>());
    }

    private static CubismPermissionGate gate(
        final List<PluginPermission> permissions,
        final List<CubismFacadeAuditEvent> auditEvents
    ) {
        return new CubismPermissionGate(
            "plugin.clipmask.test",
            permissions,
            auditEvents::add,
            Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "read"; }
            @Override public String reason() { return "test"; }
        };
    }

    private record FixedClipHost(List<ClipMaskSnapshot> snapshots)
        implements ClipMaskReadAdapter.HostOperations {
        @Override public String hostVersion() { return "5.3.02"; }
        @Override public boolean supportsClipMaskRead() { return true; }
        @Override public List<ClipMaskSnapshot> clipMasks() { return snapshots; }
    }
}
