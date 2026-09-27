package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentStatusBarAdmissionTest {

    @Test
    void admitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(environment("5.3.03", true).ordinaryReviewedRuntimeAdmitted());
        assertFalse(environment("5.3.03", false).ordinaryReviewedRuntimeAdmitted());
        assertFalse(environment("5.3.04", true).ordinaryReviewedRuntimeAdmitted());
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private static HookEnvironment environment(final String profile, final boolean admitted) {
        return HookEnvironment.builder()
            .profile(profile)
            .fullRuntimeAdmission(admitted)
            .build();
    }
}
