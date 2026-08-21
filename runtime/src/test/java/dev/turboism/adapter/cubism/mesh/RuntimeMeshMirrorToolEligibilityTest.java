package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeMeshMirrorToolEligibilityTest {

    @AfterEach
    void resetBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void preservesNativeTrueAndWidensOnlyRegisteredKnownTools() {
        final RuntimeMeshMirrorToolEligibility service = NativeMeshMirrorBridge.toolEligibility();
        final Registration registration = service.extendEligibleTools(Set.of(
            MeshEditTool.ARROW,
            MeshEditTool.ERASER,
            MeshEditTool.LASSO
        ));
        NativeMeshMirrorBridge.install(
            new RuntimeMeshMirrorAxisService(),
            new RuntimeMeshEditUiService()
        );

        assertTrue(NativeMeshMirrorBridge.adjustToolEligibility(true, NativeTool.RECT));
        assertTrue(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.ARROW));
        assertTrue(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.ERASER));
        assertTrue(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.LASSO));
        assertFalse(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.RECT));
        assertFalse(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.FUTURE_TOOL));

        registration.close();
        assertFalse(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.ARROW));
    }

    @Test
    void unboundBridgeAndUnknownRegistrationFailOpen() {
        final RuntimeMeshMirrorToolEligibility service = NativeMeshMirrorBridge.toolEligibility();
        assertThrows(
            IllegalArgumentException.class,
            () -> service.extendEligibleTools(Set.of(MeshEditTool.UNKNOWN))
        );
        assertFalse(NativeMeshMirrorBridge.adjustToolEligibility(false, NativeTool.ARROW));
    }

    private enum NativeTool {
        ARROW,
        ERASER,
        LASSO,
        RECT,
        FUTURE_TOOL
    }
}
