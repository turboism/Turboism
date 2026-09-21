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
        final NativeEditBeginHookContributor contributor = new NativeEditBeginHookContributor();
        assertTrue(contributor.admitted(environment("5.3.03", true)));
        assertFalse(contributor.admitted(environment("5.3.03", false)));
        assertFalse(contributor.admitted(environment("5.3.04", true)));
    }

    private static HookEnvironment environment(final String profile, final boolean admitted) {
        return HookEnvironment.builder()
            .profile(profile)
            .fullRuntimeAdmission(admitted)
            .build();
    }
}
