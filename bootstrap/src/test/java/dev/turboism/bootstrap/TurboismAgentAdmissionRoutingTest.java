package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurboismAgentAdmissionRoutingTest {

    @Test
    void exact5303IdentityPassesTheAgentFullRuntimeGate() {
        assertTrue(ReviewedHostArtifacts.isReviewed(ReviewedHostArtifacts.CUBISM_5_3_03));
        assertTrue(
            ReviewedHostArtifacts.admitsFullRuntime(
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION
            )
        );
    }
}
