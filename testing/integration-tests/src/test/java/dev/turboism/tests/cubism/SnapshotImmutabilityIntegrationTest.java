package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.test.fake.FakeCubismArtMesh;
import dev.turboism.test.fake.FakeCubismDeformer;
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

class SnapshotImmutabilityIntegrationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mutatingSourceListsAfterSnapshotCreationDoesNotMutateSnapshots() {
        final FakeCubismHost host = sampleHost();
        final CubismFacadeImpl facade = facadeFor(host);
        final CubismRuntimeSnapshot snapshot = facade.runtime();

        host.getActiveProject().getDocuments().clear();
        host.getActiveDocument().getModels().clear();
        host.getActiveModel().getParameters().clear();
        host.getActiveModel().getArtMeshes().clear();
        host.getActiveModel().getDeformers().clear();
        host.getSelection().getSelectedIds().clear();

        assertEquals(1, snapshot.project().orElseThrow().documents().size());
        assertEquals(1, snapshot.document().orElseThrow().model().orElseThrow().parameters().size());
        assertEquals(1, snapshot.model().orElseThrow().parameters().size());
        assertEquals(1, snapshot.model().orElseThrow().artMeshes().size());
        assertEquals(1, snapshot.model().orElseThrow().deformers().size());
        assertEquals(List.of("parameter-1", "mesh-1"), snapshot.selection().selectedObjectIds());
    }

    private static CubismFacadeImpl facadeFor(final FakeCubismHost host) {
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
        model.addParameter(new FakeCubismParameter("parameter-1", "Angle X"));
        model.addArtMesh(new FakeCubismArtMesh("mesh-1", "Face Mesh"));
        final FakeCubismDeformer deformer = new FakeCubismDeformer("deformer-1", "Root Deformer");
        deformer.setDeformerType("root");
        model.addDeformer(deformer);
        document.addModel(model);
        project.addDocument(document);
        host.start();
        host.addProject(project);
        host.setActiveProjectId(project.getId());
        host.setActiveDocument(document);
        host.setActiveModel(model);
        host.select("parameter-1");
        host.select("mesh-1");
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
