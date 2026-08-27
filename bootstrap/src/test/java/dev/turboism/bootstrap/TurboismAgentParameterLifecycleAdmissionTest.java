package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentParameterLifecycleAdmissionTest {

    @Test
    void keepsExact5303ClosedOutsideTheExplicitValidationLane() {
        assertTrue(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.02", true));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.02", false));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.03", false));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}
