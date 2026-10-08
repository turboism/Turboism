package dev.turboism.tests.cubism;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.permission.CubismPermissionException;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryServicePermissionGatingTest {

    @Test
    void eachServiceMethodDeniesWithoutItsExactPermission() {
        final CubismQueryIntegrationSupport.QueryEnvironment parameterEnvironment =
                CubismQueryIntegrationSupport.environment(CubismQueryIntegrationSupport.sampleHost());
        final CubismQueryIntegrationSupport.QueryEnvironment modelEnvironment =
                CubismQueryIntegrationSupport.environment(CubismQueryIntegrationSupport.sampleHost());

        assertDenied(
                MODEL_READ_PERMISSION,
                () -> parameterEnvironment.context().cubismRead().parameters());
        assertDenied(
                MODEL_READ_PERMISSION,
                () -> modelEnvironment.context().selectionQuery().currentSelection());
        assertDenied(
                MODEL_READ_PERMISSION,
                () -> modelEnvironment.context().selectionQuery().selectedIds(HierarchyNode.Kind.PARAMETER));
        assertDenied(
                MODEL_READ_PERMISSION,
                () -> modelEnvironment.context().modelHierarchyQuery().currentHierarchy());
        assertDenied(
                MODEL_READ_PERMISSION,
                () -> modelEnvironment.context().modelHierarchyQuery().childrenOf(new ModelObjectId("model-1")));
        assertDenied(
                MODEL_READ_PERMISSION,
                () -> modelEnvironment.context().modelHierarchyQuery().findNode(new ModelObjectId("model-1")));
    }

    @Test
    void permissionDenialDoesNotLeakPreviouslyCachedParameterData() {
        final CubismQueryIntegrationSupport.QueryEnvironment grantedEnvironment =
                CubismQueryIntegrationSupport.environment(
                        CubismQueryIntegrationSupport.sampleHost(), MODEL_READ_PERMISSION);
        assertEquals(2, grantedEnvironment.context().cubismRead().parameters().size());
        final CubismQueryIntegrationSupport.QueryEnvironment deniedEnvironment =
                CubismQueryIntegrationSupport.environment(CubismQueryIntegrationSupport.sampleHost());

        final CubismPermissionException error = assertThrows(
                CubismPermissionException.class,
                () -> deniedEnvironment.context().cubismRead().parameters());

        assertTrue(error.getMessage().contains(MODEL_READ_PERMISSION));
        assertEquals(1, deniedEnvironment.auditEvents().size());
    }

    @Test
    void pluginDisableRemovesPermissionContextByClosingPluginRegistrations() throws Exception {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
                CubismQueryIntegrationSupport.sampleHost(), MODEL_READ_PERMISSION);
        final List<String> cleanupSignals = new java.util.ArrayList<>();
        environment.disposableScope().register(() -> cleanupSignals.add("closed"));

        environment.disposableScope().close();

        assertEquals(List.of("closed"), cleanupSignals);
        assertThrows(
                IllegalStateException.class, () -> environment.disposableScope().register(() -> {}));
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
