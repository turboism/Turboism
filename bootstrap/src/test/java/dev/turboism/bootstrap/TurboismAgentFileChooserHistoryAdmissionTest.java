package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

final class TurboismAgentFileChooserHistoryAdmissionTest {

    @Test
    void admitsExact5303OnlyAfterFullRuntimeAdmission() {
        final FileChooserHistoryHookContributor contributor = new FileChooserHistoryHookContributor();
        assertTrue(contributor.admitted(HookEnvironments.admitted("5.3.03", true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.3.03", false)));
        assertFalse(contributor.admitted(HookEnvironments.admitted("5.4.00", true)));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}
