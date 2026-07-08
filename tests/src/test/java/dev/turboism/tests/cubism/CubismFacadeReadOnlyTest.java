package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismParameter;
import dev.turboism.test.fake.FakeCubismProject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismFacadeReadOnlyTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsEmptyOptionalsWhenNoProjectOrModelIsActive() {
        final FakeCubismHost host = new FakeCubismHost();
        host.start();
        final CubismFacade facade = facadeFor(host);

        assertTrue(facade.isHostPresent());
        assertTrue(facade.activeProject().isEmpty());
        assertTrue(facade.activeDocument().isEmpty());
        assertTrue(facade.activeModel().isEmpty());
        assertTrue(facade.runtime().project().isEmpty());
        assertTrue(facade.runtime().document().isEmpty());
        assertTrue(facade.runtime().model().isEmpty());
    }

    @Test
    void snapshotDataMatchesFakeHostState() {
        final FakeCubismHost host = sampleHost();
        final CubismFacade facade = facadeFor(host);

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertEquals("project-1", facade.activeProject().orElseThrow().projectId());
        assertEquals("Demo Project", runtime.project().orElseThrow().name());
        assertEquals("document-1", runtime.document().orElseThrow().documentId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals("parameter-1", runtime.parameters().get(0).id());
        assertEquals(0.5, runtime.parameters().get(0).value());
        assertEquals(List.of("parameter-1"), runtime.selection().selectedObjectIds());
    }

    @Test
    void returnedCollectionsThrowOnModification() {
        final CubismFacade facade = facadeFor(sampleHost());
        final CubismRuntimeSnapshot runtime = facade.runtime();
        final ParameterSnapshot parameter = runtime.parameters().get(0);

        final UnsupportedOperationException runtimeError = assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.parameters().add(parameter)
        );
        assertNull(runtimeError.getMessage());

        final UnsupportedOperationException modelError = assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.model().orElseThrow().parameters().add(parameter)
        );
        assertNull(modelError.getMessage());

        final UnsupportedOperationException selectionError = assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.selection().selectedObjectIds().add("parameter-2")
        );
        assertNull(selectionError.getMessage());
    }

    @Test
    void oldSnapshotsRemainUnchangedAfterFakeHostInvalidation() {
        final FakeCubismHost host = sampleHost();
        final CubismFacade facade = facadeFor(host);
        final CubismRuntimeSnapshot oldSnapshot = facade.runtime();

        host.getActiveModel().getParameters().get(0).setValue(1.0F);
        host.getActiveModel().addParameter(new FakeCubismParameter("parameter-2", "Added Parameter"));
        host.clearSelection();
        host.bumpInvalidationToken();

        assertEquals(0.5, oldSnapshot.parameters().get(0).value());
        assertEquals(1, oldSnapshot.parameters().size());
        assertEquals(List.of("parameter-1"), oldSnapshot.selection().selectedObjectIds());
        assertEquals(2, facade.runtime().parameters().size());
        assertTrue(facade.runtime().selection().selectedObjectIds().isEmpty());
    }

    private static CubismFacade facadeFor(final FakeCubismHost host) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        return new CubismFacadeImpl(new FakeHostSnapshotSource(host), new CubismPermissionGate(
            "plugin.demo",
            List.of(permission(CubismFacadeImpl.PROJECT_READ_PERMISSION), permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
            auditEvents::add,
            FIXED_CLOCK
        ));
    }

    private static FakeCubismHost sampleHost() {
        final FakeCubismHost host = new FakeCubismHost();
        final FakeCubismProject project = new FakeCubismProject("project-1", "Demo Project");
        final FakeCubismDocument document = new FakeCubismDocument("document-1", "Demo Document");
        final FakeCubismModel model = new FakeCubismModel("model-1", "Demo Model");
        final FakeCubismParameter parameter = new FakeCubismParameter("parameter-1", "Angle X");
        parameter.setValue(0.5F);
        parameter.setDefaultValue(0.0F);
        parameter.setMinValue(-1.0F);
        parameter.setMaxValue(1.0F);
        model.addParameter(parameter);
        document.addModel(model);
        project.addDocument(document);
        host.start();
        host.addProject(project);
        host.setActiveProjectId(project.getId());
        host.setActiveDocument(document);
        host.setActiveModel(model);
        host.select(parameter.getId());
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
