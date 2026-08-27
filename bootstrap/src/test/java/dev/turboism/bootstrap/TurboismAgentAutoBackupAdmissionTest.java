package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentAutoBackupAdmissionTest {

    @Test
    void keepsExact5303ClosedBehindFullRuntimeAdmission() {
        assertTrue(TurboismAgent.autoBackupRuntimeAdmitted("5.3.02", true));
        assertFalse(TurboismAgent.autoBackupRuntimeAdmitted("5.3.02", false));
        assertFalse(TurboismAgent.autoBackupRuntimeAdmitted("5.3.03", false));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}
