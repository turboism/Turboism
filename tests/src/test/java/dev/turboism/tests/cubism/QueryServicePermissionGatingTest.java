package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.PARAMETER_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServicePermissionGatingTest {

    @Test
    void eachServiceMethodDeniesWithoutItsExactPermission() {
        final CubismQueryIntegrationSupport.QueryEnvironment parameterEnvironment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );
        final CubismQueryIntegrationSupport.QueryEnvironment modelEnvironment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            PARAMETER_READ_PERMISSION
        );

        assertDenied(PARAMETER_READ_PERMISSION, () -> parameterEnvironment.context().parameterQuery().findById(new ParameterId("param-angle-x")));
        assertDenied(PARAMETER_READ_PERMISSION, () -> parameterEnvironment.context().parameterQuery().listAll());
        assertDenied(PARAMETER_READ_PERMISSION, () -> parameterEnvironment.context().parameterQuery().exists(new ParameterId("param-angle-x")));
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().selectionQuery().currentSelection());
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().selectionQuery().selectedIds(HierarchyNode.Kind.PARAMETER));
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().selectionQuery().onSelectionChanged(event -> { }));
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().modelHierarchyQuery().currentHierarchy());
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().modelHierarchyQuery().childrenOf(new ModelObjectId("model-1")));
        assertDenied(MODEL_READ_PERMISSION, () -> modelEnvironment.context().modelHierarchyQuery().findNode(new ModelObjectId("model-1")));
    }

    @Test
    void permissionDenialDoesNotLeakPreviouslyCachedParameterData() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment grantedEnvironment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );
        assertEquals(2, grantedEnvironment.context().parameterQuery().listAll().size());
        final CubismQueryIntegrationSupport.QueryEnvironment deniedEnvironment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, () -> deniedEnvironment.context().parameterQuery().findById(new ParameterId("param-angle-x")));

        assertTrue(error.getMessage().contains(PARAMETER_READ_PERMISSION));
        assertEquals(1, deniedEnvironment.auditEvents().size());
    }

    @Test
    void pluginDisableRemovesPermissionContextByClosingPluginRegistrations() throws Exception {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );
        final List<String> cleanupSignals = new java.util.ArrayList<>();
        environment.disposableScope().register(() -> cleanupSignals.add("closed"));

        environment.disposableScope().close();

        assertEquals(List.of("closed"), cleanupSignals);
        assertThrows(IllegalStateException.class, () -> environment.disposableScope().register(() -> { }));
    }

    private static void assertDenied(final String permissionId, final ThrowingQuery query) {
        final CubismPermissionException error = assertThrows(CubismPermissionException.class, query::run);
        assertTrue(error.getMessage().contains(permissionId));
    }

    @FunctionalInterface
    private interface ThrowingQuery {
        void run() throws CubismServiceException;
    }
}
