package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartupSuppressionVerificationManifestTest {

    @Test
    void pinsEveryReviewedArtifactToItsRecordAliasSurface() {
        assertEquals(
                StartupSuppressionVerificationManifest.LEGACY_REQUIRED_ALIASES,
                StartupSuppressionVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_0_00)
                        .requiredAliases());
        assertEquals(
                StartupSuppressionVerificationManifest.LEGACY_REQUIRED_ALIASES,
                StartupSuppressionVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_0_06)
                        .requiredAliases());
        assertEquals(
                StartupSuppressionVerificationManifest.REQUIRED_ALIASES,
                StartupSuppressionVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_1_00)
                        .requiredAliases());
        assertEquals(
                StartupSuppressionVerificationManifest.REQUIRED_ALIASES,
                StartupSuppressionVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_04)
                        .requiredAliases());
        assertEquals(
                ReviewedHostArtifacts.all().size(),
                StartupSuppressionVerificationManifest.reviewedCubismVersions().size());
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            StartupSuppressionVerificationManifest.forArtifact(artifact);
        }
    }

    @Test
    void exact5303ValidationCandidateRequiresTheCompoundRunnerIdentity() {
        assertTrue(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
                "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
                "5303",
                "splash-update-information",
                "startup-suppression-5303-r1"));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
                null, "5303", "splash-update-information", "startup-suppression-5303-r1"));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
                "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
                "5302",
                "splash-update-information",
                "startup-suppression-5303-r1"));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
                "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE", "5303", "splash-only", "startup-suppression-5303-r1"));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
                "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE", "5303", "splash-update-information", ""));
    }
}
