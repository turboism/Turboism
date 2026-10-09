package dev.turboism.adapter.cubism.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

class StartupSuppressionProfileTest {

    @Test
    void selectsIndependentExactProfilesForEveryReviewedCubismArtifact() {
        assertEquals(20, ReviewedHostArtifacts.all().size());
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            assertTrue(
                    StartupSuppressionProfile.forArtifact(artifact).isPresent(),
                    () -> "no suppression profile for reviewed artifact " + artifact);
        }
    }

    @Test
    void selectsIndependentExactProfilesForBothReviewedCubismArtifacts() {
        final StartupSuppressionProfile cubism52 = StartupSuppressionProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_2_03)
                .orElseThrow();
        final StartupSuppressionProfile cubism53 = StartupSuppressionProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_3_02)
                .orElseThrow();

        assertEquals("5.2.03", cubism52.cubismVersion());
        assertEquals("()Lcom/live2d/ui/window/X;", cubism52.splashMethod().descriptor());
        assertEquals("5.3.02", cubism53.cubismVersion());
        assertEquals("()Lcom/live2d/ui/window/V;", cubism53.splashMethod().descriptor());
    }

    @Test
    void bindsTheLegacySplashFactoryAndAbsentInformationCallOnCubism50x() {
        final StartupSuppressionProfile cubism500 = StartupSuppressionProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_0_00)
                .orElseThrow();
        final StartupSuppressionProfile cubism503 = StartupSuppressionProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_0_03)
                .orElseThrow();
        final StartupSuppressionProfile cubism5103 = StartupSuppressionProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_1_03)
                .orElseThrow();

        assertEquals("c", cubism500.splashMethod().name());
        assertEquals("()Lcom/live2d/ui/window/Y;", cubism500.splashMethod().descriptor());
        assertTrue(cubism500.informationCall().isEmpty());
        assertEquals("e", cubism503.splashMethod().name());
        assertEquals("()Lcom/live2d/ui/window/X;", cubism503.splashMethod().descriptor());
        assertTrue(cubism503.informationCall().isEmpty());
        assertEquals("e", cubism5103.splashMethod().name());
        assertEquals("()Lcom/live2d/ui/window/X;", cubism5103.splashMethod().descriptor());
        assertTrue(cubism5103.informationCall().isPresent());
    }

    @Test
    void keepsEveryReviewedVersionAliasSetConsistentWithItsPinBlock() {
        assertEquals(
                StartupSuppressionProfile.reviewedClassSha256().keySet(),
                StartupSuppressionProfile.reviewedCubismVersions());
    }

    @Test
    void rejectsEveryUnreviewedArtifactIdentity() {
        assertTrue(StartupSuppressionProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
        assertFalse(StartupSuppressionProfile.forReviewedVersion("9.9.99", ReviewedHostArtifacts.CUBISM_5_3_02)
                .isPresent());
    }
}
