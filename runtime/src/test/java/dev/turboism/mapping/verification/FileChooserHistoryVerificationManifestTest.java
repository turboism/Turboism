package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChooserHistoryVerificationManifestTest {

    @Test
    void requiresTheFullExact5303ValidationIdentity() {
        assertTrue(FileChooserHistoryVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FILE_CHOOSER_HISTORY_HOOK_CANDIDATE",
            "5303",
            "file-chooser-history-hook-5303",
            "file-chooser-r1"
        ));
        assertFalse(FileChooserHistoryVerificationManifest.admits5303ValidationCandidate(
            null,
            "5303",
            "file-chooser-history-hook-5303",
            "file-chooser-r1"
        ));
        assertFalse(FileChooserHistoryVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FILE_CHOOSER_HISTORY_HOOK_CANDIDATE",
            "5302",
            "file-chooser-history-hook-5303",
            "file-chooser-r1"
        ));
        assertFalse(FileChooserHistoryVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FILE_CHOOSER_HISTORY_HOOK_CANDIDATE",
            "5303",
            "file-chooser-history-hook-5303",
            " "
        ));
    }
}
