package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismProject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadePermissionGatingTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);
    private static final String PLUGIN_ID = "plugin.demo";

    @Test
    void activeProjectThrowsWhenProjectReadPermissionIsMissing() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacade facade = facadeWith(sampleHost(), auditEvents, CubismFacadeImpl.MODEL_READ_PERMISSION);

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, facade::activeProject);

        assertEquals(
            "Plugin plugin.demo is missing required Cubism permission turboism.cubism.project.read for activeProject",
            error.getMessage()
        );
        assertEquals(1, auditEvents.size());
        assertAuditEvent(auditEvents.get(0), CubismFacadeImpl.PROJECT_READ_PERMISSION, "activeProject");
    }

    @Test
    void activeModelAndActiveDocumentThrowWhenModelReadPermissionIsMissing() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacade facade = facadeWith(sampleHost(), auditEvents, CubismFacadeImpl.PROJECT_READ_PERMISSION);

        final CubismPermissionException modelError = assertThrows(CubismPermissionException.class, facade::activeModel);
        final CubismPermissionException documentError = assertThrows(CubismPermissionException.class, facade::activeDocument);

        assertEquals(
            "Plugin plugin.demo is missing required Cubism permission turboism.cubism.model.read for activeModel",
            modelError.getMessage()
        );
        assertEquals(
            "Plugin plugin.demo is missing required Cubism permission turboism.cubism.model.read for activeDocument",
            documentError.getMessage()
        );
        assertEquals(2, auditEvents.size());
        assertAuditEvent(auditEvents.get(0), CubismFacadeImpl.MODEL_READ_PERMISSION, "activeModel");
        assertAuditEvent(auditEvents.get(1), CubismFacadeImpl.MODEL_READ_PERMISSION, "activeDocument");
    }

    @Test
    void runtimeRedactsProjectPortionWhenProjectReadIsMissingButModelReadIsGranted() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacade facade = facadeWith(sampleHost(), auditEvents, CubismFacadeImpl.MODEL_READ_PERMISSION);

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertTrue(runtime.project().isEmpty());
        assertEquals("document-1", runtime.document().orElseThrow().documentId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals(1, auditEvents.size());
        assertAuditEvent(auditEvents.get(0), CubismFacadeImpl.PROJECT_READ_PERMISSION, "runtime");
    }

    @Test
    void deniedRuntimeRecordsAuditEventWhenModelReadPermissionIsMissing() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacade facade = facadeWith(sampleHost(), auditEvents, CubismFacadeImpl.PROJECT_READ_PERMISSION);

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, facade::runtime);

        assertEquals(
            "Plugin plugin.demo is missing required Cubism permission turboism.cubism.model.read for runtime",
            error.getMessage()
        );
        assertEquals(1, auditEvents.size());
        assertAuditEvent(auditEvents.get(0), CubismFacadeImpl.MODEL_READ_PERMISSION, "runtime");
    }

    private static CubismFacade facadeWith(
        final FakeCubismHost host,
        final List<CubismFacadeAuditEvent> auditEvents,
        final String... permissionIds
    ) {
        return new CubismFacadeImpl(new FakeHostSnapshotSource(host), new CubismPermissionGate(
            PLUGIN_ID,
            List.of(permissionIds).stream().map(FacadePermissionGatingTest::permission).toList(),
            auditEvents::add,
            FIXED_CLOCK
        ));
    }

    private static void assertAuditEvent(
        final CubismFacadeAuditEvent event,
        final String permissionId,
        final String methodName
    ) {
        assertEquals(PLUGIN_ID, event.pluginId());
        assertEquals(permissionId, event.permissionId());
        assertEquals(methodName, event.methodName());
        assertEquals(FIXED_CLOCK.instant(), event.timestamp());
    }

    private static FakeCubismHost sampleHost() {
        final FakeCubismHost host = new FakeCubismHost();
        final FakeCubismProject project = new FakeCubismProject("project-1", "Demo Project");
        final FakeCubismDocument document = new FakeCubismDocument("document-1", "Demo Document");
        final FakeCubismModel model = new FakeCubismModel("model-1", "Demo Model");
        document.addModel(model);
        project.addDocument(document);
        host.start();
        host.addProject(project);
        host.setActiveProjectId(project.getId());
        host.setActiveDocument(document);
        host.setActiveModel(model);
        return host;
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
}
