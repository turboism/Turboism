package dev.turboism.internal.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class CoreFrameworkUpdatesTest {
    @Test void legacyDiscoveryCannotClaimAnInstallIsAvailable() {
        final var updates = CoreUpdateService.unavailable().frameworkUpdates();
        assertEquals(CoreFrameworkUpdates.Phase.UNAVAILABLE, updates.snapshot().phase());
        assertEquals(CoreFrameworkUpdates.Phase.UNAVAILABLE, updates.download().toCompletableFuture().join().phase());
        assertEquals(CoreFrameworkUpdates.Phase.UNAVAILABLE,
                updates.installOnExit().toCompletableFuture().join().phase());
        updates.cancel();
    }

    @Test void verifiedProgressCannotExceedAuthenticatedTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new CoreFrameworkUpdates.Snapshot(CoreFrameworkUpdates.Phase.DOWNLOADING, "1.0.0", 2, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new CoreFrameworkUpdates.Snapshot(CoreFrameworkUpdates.Phase.DOWNLOADING, "1.0.0", -1, 1, ""));
    }
}
