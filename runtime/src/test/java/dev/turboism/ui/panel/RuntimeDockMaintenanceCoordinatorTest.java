package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    @Test
    void unbindDoesNotWaitForAnInFlightHostCleanup() throws Exception {
        final RuntimeDockMaintenanceCoordinator coordinator =
            new RuntimeDockMaintenanceCoordinator();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Registration registration = coordinator.bind(3, () -> {
            entered.countDown();
            await(release);
        });
        final Thread cleanup = new Thread(coordinator::cleanEmptyDocks);
        cleanup.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), registration::close);
        } finally {
            release.countDown();
            cleanup.join(2_000L);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup interrupted", exception);
        }
    }
}
