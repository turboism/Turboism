package dev.turboism.adapter.cubism;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismFacadeImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void grantedPermissionsReturnSnapshots() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertEquals("project-1", facade.activeProject().orElseThrow().projectId());
        assertEquals("document-1", facade.activeDocument().orElseThrow().documentId());
        assertEquals("model-1", facade.activeModel().orElseThrow().modelId());
        assertEquals("project-1", runtime.project().orElseThrow().projectId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals(3, runtime.modelObjects().size());
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void deniedProjectPermissionThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            facade::activeProject
        );

        assertTrue(error.getMessage().contains(CubismFacadeImpl.PROJECT_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals("plugin.demo", auditEvents.get(0).pluginId());
        assertEquals(CubismFacadeImpl.PROJECT_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("activeProject", auditEvents.get(0).methodName());
        assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
    }

    @Test
    void runtimeRedactsProjectWhenOnlyProjectPermissionIsDenied() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertTrue(runtime.project().isEmpty());
        assertEquals("document-1", runtime.document().orElseThrow().documentId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals("runtime", auditEvents.get(0).methodName());
        assertEquals(CubismFacadeImpl.PROJECT_READ_PERMISSION, auditEvents.get(0).permissionId());
    }

    @Test
    void deniedModelPermissionThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION)
        ));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            facade::activeModel
        );

        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("activeModel", auditEvents.get(0).methodName());
    }

    @Test
    void noActiveHostReturnsEmptyOptionalsAndEmptyRuntime() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(emptySource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        assertFalse(facade.isHostPresent());
        assertTrue(facade.activeProject().isEmpty());
        assertTrue(facade.activeDocument().isEmpty());
        assertTrue(facade.activeModel().isEmpty());
        assertTrue(facade.runtime().project().isEmpty());
        assertTrue(facade.runtime().document().isEmpty());
        assertTrue(facade.runtime().model().isEmpty());
        assertTrue(auditEvents.isEmpty());
    }

    private CubismFacadeImpl facadeWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions
    ) {
        return new CubismFacadeImpl(source, new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        ));
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

    private static HostSnapshotSource sampleSource() {
        final HostSnapshotSource.HostModel model = new HostSnapshotSource.HostModel(
            "model-1",
            "Model",
            List.of(new HostSnapshotSource.HostParameter("param-1", "Param", 1.0, 0.0, -1.0, 1.0, true, true)),
            List.of(new HostSnapshotSource.HostArtMesh("mesh-1", "Mesh", Optional.of("texture-1"), true, true)),
            List.of(new HostSnapshotSource.HostDeformer("deformer-1", "Deformer", DeformerType.ROOT, Optional.empty(), List.of()))
        );
        final HostSnapshotSource.HostDocument document = new HostSnapshotSource.HostDocument(
            "document-1",
            "Document",
            "models/demo/model.cdi3.json",
            Optional.of(Path.of("models/demo/model.cdi3.json")),
            Optional.of(model)
        );
        final HostSnapshotSource.HostProject project = new HostSnapshotSource.HostProject(
            "project-1",
            "Project",
            Optional.of(Path.of("projects/demo")),
            List.of(document)
        );
        return new StubHostSnapshotSource(
            Optional.of(project),
            Optional.of(document),
            Optional.of(model),
            new HostSnapshotSource.HostSelection(List.of("param-1"), Optional.of("param-1"), Optional.empty(), Optional.empty()),
            true
        );
    }

    private static HostSnapshotSource emptySource() {
        return new StubHostSnapshotSource(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HostSnapshotSource.HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
            false
        );
    }

    private record StubHostSnapshotSource(
        Optional<HostProject> project,
        Optional<HostDocument> document,
        Optional<HostModel> model,
        HostSelection selection,
        boolean hostPresent
    ) implements HostSnapshotSource {
        @Override
        public Optional<HostProject> activeProject() {
            return project;
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return document;
        }

        @Override
        public Optional<HostModel> activeModel() {
            return model;
        }

        @Override
        public boolean isHostPresent() {
            return hostPresent;
        }

        @Override
        public long invalidationToken() {
            return 0L;
        }
    }
}
