package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the native edit entry hook's admission gate.
 *
 * <p>The hook observes an existing native method and promotes no host capability of its own, so it
 * is admitted with the ordinary reviewed runtime; the exact Cubism versions whose records carry the
 * reviewed entry are checked separately by the installer.</p>
 */
class TurboismAgentNativeEditBeginAdmissionTest {

    @Test
    void theHookFollowsTheOrdinaryReviewedRuntime() {
        assertTrue(TurboismAgent.nativeEditBeginHookRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.nativeEditBeginHookRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.nativeEditBeginHookRuntimeAdmitted("5.3.04", true));
    }
}
