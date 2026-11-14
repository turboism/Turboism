package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the auto-mesh guard admission gate in {@link AutoMeshGuardHookContributor}. */
final class TurboismAgentAutoMeshGuardAdmissionTest {

    @Test
    void autoMeshGuardFollowsOrdinaryReviewedAdmission() {
        final AutoMeshGuardHookContributor contributor = new AutoMeshGuardHookContributor();
        assertTrue(contributor.admitted(HookEnvironments.admitted("5.0.00", true)));
        assertTrue(contributor.admitted(HookEnvironments.admitted("5.0.06", true)));
        assertTrue(contributor.admitted(HookEnvironments.admitted("5.2.03", true)));
        assertTrue(contributor.admitted(HookEnvironments.admitted("5.3.04", true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.3.02", false)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.0.00", false)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.4.00", true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.3", true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.3.04.1", true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted(null, true)));
    }

    @Test
    void autoMeshGuardCoversEveryReviewedRelease() {
        for (final String version : dev.turboism.automesh.host.AutoMeshHostProfile.supportedHostVersions()) {
            assertTrue(
                    AutoMeshGuardHookContributor.runtimeAdmitted(version, true),
                    "every reviewed release carries a funnel profile: " + version);
        }
    }
}
