package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSuppressionVerificationManifestTest {

    @Test
    void exact5303ValidationCandidateRequiresTheCompoundRunnerIdentity() {
        assertTrue(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
            "5303",
            "splash-update-information",
            "startup-suppression-5303-r1"
        ));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
            null,
            "5303",
            "splash-update-information",
            "startup-suppression-5303-r1"
        ));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
            "5302",
            "splash-update-information",
            "startup-suppression-5303-r1"
        ));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
            "5303",
            "splash-only",
            "startup-suppression-5303-r1"
        ));
        assertFalse(StartupSuppressionVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE",
            "5303",
            "splash-update-information",
            ""
        ));
    }
}
