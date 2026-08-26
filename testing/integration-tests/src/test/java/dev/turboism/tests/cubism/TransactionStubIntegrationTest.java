package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionStubIntegrationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);
    @Test
    void removedReadOnlyTransactionStubIsNotShippedWithTheRuntime() throws Exception {
        final FakeCubismHost host = sampleHost();
        final CubismFacade facade = facadeFor(host);

        assertTrue(facade.isHostPresent());
        assertTrue(facade.activeModel().isPresent());
        assertThrows(
            ClassNotFoundException.class,
            () -> Class.forName("dev.turboism.adapter.cubism.CubismTransactionStub")
        );
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
