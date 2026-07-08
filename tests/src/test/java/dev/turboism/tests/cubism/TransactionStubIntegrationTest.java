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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionStubIntegrationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);
    private static final String WRITE_DISABLED_MESSAGE = "M6 read-only boundary: Cubism transaction writes are disabled";

    @Test
    void transactionStubThrowsUnsupportedOperationWhenFacadeAndHostExist() throws Exception {
        final FakeCubismHost host = sampleHost();
        final CubismFacade facade = facadeFor(host);

        assertTrue(facade.isHostPresent());
        assertTrue(facade.activeModel().isPresent());

        final Class<?> stubClass = Class.forName("dev.turboism.adapter.cubism.CubismTransactionStub");
        final Constructor<?> constructor = stubClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Object stub = constructor.newInstance();
        final Method setParameterValue = stubClass.getDeclaredMethod(
            "setParameterValue",
            String.class,
            String.class,
            double.class
        );
        setParameterValue.setAccessible(true);

        final InvocationTargetException reflectiveError = assertThrowsReflectiveWrite(stub, setParameterValue);
        final Throwable writeError = reflectiveError.getCause();

        assertInstanceOf(UnsupportedOperationException.class, writeError);
        assertEquals(WRITE_DISABLED_MESSAGE, writeError.getMessage());
    }

    private static InvocationTargetException assertThrowsReflectiveWrite(final Object stub, final Method method) {
        try {
            method.invoke(stub, "model-1", "parameter-1", 0.25D);
        } catch (InvocationTargetException error) {
            return error;
        } catch (IllegalAccessException error) {
            throw new AssertionError("CubismTransactionStub write method should be reflectively accessible", error);
        }
        throw new AssertionError("CubismTransactionStub write method should throw UnsupportedOperationException");
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
