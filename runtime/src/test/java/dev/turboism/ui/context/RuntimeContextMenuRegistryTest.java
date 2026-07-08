package dev.turboism.ui.context;

import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextMenuRegistryTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";

    @Test
    void contributeReturnsRegistrationAndContributionIsVisible() {
        // Given
        RuntimeContextMenuRegistry registry = new RuntimeContextMenuRegistry(
            (permissionId, operation) -> assertEquals(PermissionIds.TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE, permissionId),
            PLUGIN_ID
        );
        ContextMenuRegistry.ContextMenuContribution contribution = contribution("probe.context");

        // When
        Registration registration = registry.contribute(contribution);

        // Then
        assertEquals(PLUGIN_ID, registry.pluginId());
        assertEquals(1, registry.contributions().size());
        assertEquals(contribution, registry.contributions().get(0));
        registration.close();
    }

    @Test
    void closingRegistrationRemovesContribution() {
        // Given
        RuntimeContextMenuRegistry registry = new RuntimeContextMenuRegistry((permissionId, operation) -> { }, PLUGIN_ID);
        Registration registration = registry.contribute(contribution("probe.context"));

        // When
        registration.close();

        // Then
        assertTrue(registry.contributions().isEmpty());
    }

    @Test
    void contributeWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeContextMenuRegistry registry = new RuntimeContextMenuRegistry(
            (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); },
            PLUGIN_ID
        );

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.contribute(contribution("probe.context"))
        );
        assertEquals("probe.context denied", exception.getMessage());
        assertTrue(registry.contributions().isEmpty());
    }

    private static ContextMenuRegistry.ContextMenuContribution contribution(final String id) {
        return new ContextMenuRegistry.ContextMenuContribution(
            id,
            "Probe",
            null,
            "parameter",
            5
        );
    }
}
