package dev.turboism.adapter.cubism.startup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSuppressionProfileTest {

    @Test
    void selectsIndependentExactProfilesForBothReviewedCubismArtifacts() {
        final StartupSuppressionProfile cubism52 = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_2_03
        ).orElseThrow();
        final StartupSuppressionProfile cubism53 = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ).orElseThrow();

        assertEquals("5.2.03", cubism52.cubismVersion());
        assertEquals("()Lcom/live2d/ui/window/X;", cubism52.splashMethod().descriptor());
        assertEquals("5.3.02", cubism53.cubismVersion());
        assertEquals("()Lcom/live2d/ui/window/V;", cubism53.splashMethod().descriptor());
    }

    @Test
    void rejectsEveryUnreviewedArtifactIdentity() {
        assertTrue(StartupSuppressionProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ).isEmpty());
    }
}
