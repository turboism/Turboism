package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentParameterLifecycleAdmissionTest {

    @Test
    void admitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}
