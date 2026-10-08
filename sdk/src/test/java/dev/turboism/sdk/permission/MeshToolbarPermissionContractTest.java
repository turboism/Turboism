package dev.turboism.sdk.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MeshToolbarPermissionContractTest {

    @Test
    void exposesTheDedicatedMeshToolbarContributionId() {
        assertEquals("turboism.ui.toolbar.mesh.contribute", PermissionIds.TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE);
    }
}
