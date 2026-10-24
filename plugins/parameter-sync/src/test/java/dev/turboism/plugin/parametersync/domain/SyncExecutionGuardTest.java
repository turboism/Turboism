package dev.turboism.plugin.parametersync.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SyncExecutionGuardTest {

    @Test
    void allowsSyncingWhenIdle() {
        final SyncExecutionGuard guard = new SyncExecutionGuard();
        assertTrue(guard.canSyncFrom("ParamA"));
        assertTrue(guard.canSyncFrom("ParamB"));
    }

    @Test
    void blocksParticipatingParametersDuringExecution() {
        final SyncExecutionGuard guard = new SyncExecutionGuard();
        final AtomicBoolean verifiedInside = new AtomicBoolean(false);

        guard.executeGuarded(List.of("ParamA", "ParamB"), () -> {
            assertFalse(guard.canSyncFrom("ParamA"));
            assertFalse(guard.canSyncFrom("ParamB"));
            assertTrue(guard.canSyncFrom("ParamC"));
            verifiedInside.set(true);
        });

        assertTrue(verifiedInside.get());
        assertTrue(guard.canSyncFrom("ParamA"));
        assertTrue(guard.canSyncFrom("ParamB"));
    }

    @Test
    void cleansUpEvenWhenActionThrows() {
        final SyncExecutionGuard guard = new SyncExecutionGuard();

        try {
            guard.executeGuarded(List.of("ParamA"), () -> {
                throw new RuntimeException("test error");
            });
        } catch (RuntimeException ignored) {
        }

        assertTrue(guard.canSyncFrom("ParamA"));
    }
}
