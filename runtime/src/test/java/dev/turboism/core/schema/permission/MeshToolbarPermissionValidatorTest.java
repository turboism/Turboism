package dev.turboism.core.schema.permission;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MeshToolbarPermissionValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsTheDedicatedMeshToolbarContributionPermission() {
        final var permission = JSON.createObjectNode();
        permission.put("format", "turboism.permission");
        permission.put("schemaVersion", 1);
        permission.put("id", "turboism.ui.toolbar.mesh.contribute");
        permission.put("scope", "application");
        permission.put("reason", "Contribute a custom mesh tool");

        assertTrue(new PermissionValidator()
                .validate(permission, "mesh-toolbar.json")
                .isEmpty());
    }
}
