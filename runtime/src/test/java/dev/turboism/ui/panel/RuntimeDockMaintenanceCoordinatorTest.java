package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeDockMaintenanceCoordinatorTest {

    @Test
    void routesOnlyWhileCurrentHostTargetIsBound() {
        RuntimeDockMaintenanceCoordinator coordinator = new RuntimeDockMaintenanceCoordinator();
        assertThrows(IllegalStateException.class, coordinator::cleanEmptyDocks);

        final boolean[] cleaned = {false};
        Registration registration = coordinator.bind(3, () -> cleaned[0] = true);
        coordinator.cleanEmptyDocks();
        assertTrue(cleaned[0]);
        registration.close();

        assertThrows(IllegalStateException.class, coordinator::cleanEmptyDocks);
    }
}
