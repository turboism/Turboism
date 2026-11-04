package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.pluginmanagement.PendingPluginOperations.ApplyResult;
import dev.turboism.pluginmanagement.PendingPluginOperations.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginPendingStartupPolicyTest {
    @Test
    void completeRollbackPermitsOldPluginsButNeverConfirmsFrameworkHealth() {
        final var result = new ApplyResult(Status.ROLLED_BACK, "PLUGIN_PENDING_ROLLED_BACK");
        assertDoesNotThrow(() -> KernelRuntime.requireSafePendingPlugins(result));
        final var confirmed = new AtomicInteger();
        final var diagnostics = new ArrayList<String>();
        KernelRuntime.confirmFrameworkHealthAfterPluginApply(result, confirmed::incrementAndGet, diagnostics::add);
        assertEquals(0, confirmed.get());
        assertEquals(java.util.List.of("UPDATE_STARTUP_NOT_CONFIRMED: PLUGIN_PENDING_ROLLED_BACK"), diagnostics);
    }

    @Test
    void suspendedFailureAllowsOldPluginsButNeverConfirmsAFrameworkUpdate() {
        final var result = new ApplyResult(Status.DEFERRED, "PLUGIN_PENDING_REVIEW_REQUIRED");
        assertDoesNotThrow(() -> KernelRuntime.requireSafePendingPlugins(result));
        KernelRuntime.confirmFrameworkHealthAfterPluginApply(
                result,
                () -> fail("Must not confirm"),
                code -> assertEquals("UPDATE_STARTUP_NOT_CONFIRMED: PLUGIN_PENDING_REVIEW_REQUIRED", code));
    }

    @Test
    void appliedOrEmptyIntentMayReachTheRealHealthVerifier() {
        final var result = new ApplyResult(Status.APPLIED, "PLUGIN_PENDING_EMPTY");
        assertDoesNotThrow(() -> KernelRuntime.requireSafePendingPlugins(result));
        final var confirmed = new AtomicInteger();
        KernelRuntime.confirmFrameworkHealthAfterPluginApply(result, confirmed::incrementAndGet, ignored -> fail());
        assertEquals(1, confirmed.get());
    }

    @Test
    void backupOrRollbackFailureStillBlocksStartupAndNeverConfirms() {
        for (String code : java.util.List.of("PLUGIN_PENDING_BACKUP_FAILED", "PLUGIN_PENDING_RECOVERY_REQUIRED")) {
            final var result = new ApplyResult(Status.RECOVERY_REQUIRED, code);
            assertEquals(
                    code,
                    assertThrows(IOException.class, () -> KernelRuntime.requireSafePendingPlugins(result))
                            .getMessage());
            final var confirmed = new AtomicInteger();
            KernelRuntime.confirmFrameworkHealthAfterPluginApply(result, confirmed::incrementAndGet, ignored -> {});
            assertEquals(0, confirmed.get());
        }
    }
}
