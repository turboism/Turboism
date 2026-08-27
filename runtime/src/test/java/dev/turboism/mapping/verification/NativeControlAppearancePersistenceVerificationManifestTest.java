package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeControlAppearancePersistenceVerificationManifestTest {

    @Test
    void exactCompoundLaneAdmitsEveryPhase() {
        for (String mode : NativeControlAppearancePersistenceVerificationManifest.MODES) {
            assertTrue(admits(
                NativeControlAppearancePersistenceVerificationManifest.TOKEN,
                "5303",
                mode,
                "persist-r1"
            ));
        }
    }

    @Test
    void rejectsWrongIdentityComponents() {
        assertFalse(admits("wrong", "5303", writeMode(), "persist-r1"));
        assertFalse(admits(
            NativeControlAppearancePersistenceVerificationManifest.TOKEN,
            "5302",
            writeMode(),
            "persist-r1"
        ));
        assertFalse(admits(
            NativeControlAppearancePersistenceVerificationManifest.TOKEN,
            "5303",
            "native-control-appearance-write-5303",
            "persist-r1"
        ));
        assertFalse(admits(
            NativeControlAppearancePersistenceVerificationManifest.TOKEN,
            "5303",
            writeMode(),
            " "
        ));
    }

    private static boolean admits(
        final String token,
        final String version,
        final String mode,
        final String runId
    ) {
        return NativeControlAppearancePersistenceVerificationManifest
            .admits5303ValidationCandidate(token, version, mode, runId);
    }

    private static String writeMode() {
        return NativeControlAppearancePersistenceVerificationManifest.WRITE_MODE;
    }
}
