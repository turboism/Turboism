package dev.turboism.adapter.cubism.startup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSuppressionProfileTest {

    @Test
    void selectsIndependentExactProfilesForBothReviewedCubismArtifacts() {
        final StartupSuppressionProfile cubism52 = StartupSuppressionProfile.forArtifact(
            new HostArtifactDigest(
                40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
            )
        ).orElseThrow();
        final StartupSuppressionProfile cubism53 = StartupSuppressionProfile.forArtifact(
            new HostArtifactDigest(
                41_922_739L,
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
            )
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
