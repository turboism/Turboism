package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutoBackupVerificationManifestAdmissionTest {

    @Test
    void exact5303ValidationCandidateRequiresTheCompoundRunnerIdentity() {
        assertTrue(AutoBackupVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_AUTOBACKUP_CANDIDATE",
            "5303",
            "matrix",
            "backup-5303-r1"
        ));
        assertFalse(AutoBackupVerificationManifest.admits5303ValidationCandidate(
            null, "5303", "matrix", "backup-5303-r1"
        ));
        assertFalse(AutoBackupVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_AUTOBACKUP_CANDIDATE", "5302", "matrix", "backup-5303-r1"
        ));
        assertFalse(AutoBackupVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_AUTOBACKUP_CANDIDATE", "5303", "settings-only", "backup-5303-r1"
        ));
        assertFalse(AutoBackupVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_AUTOBACKUP_CANDIDATE", "5303", "matrix", ""
        ));
    }
}
