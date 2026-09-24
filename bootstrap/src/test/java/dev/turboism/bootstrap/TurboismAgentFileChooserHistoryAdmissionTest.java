package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentFileChooserHistoryAdmissionTest {

    @Test
    void admitsExact5303OnlyAfterFullRuntimeAdmission() {
        final FileChooserHistoryHookContributor contributor = new FileChooserHistoryHookContributor();
        assertTrue(contributor.admitted(environment("5.3.03", true)));
        assertFalse(contributor.admitted(environment("5.3.03", false)));
        assertFalse(contributor.admitted(environment("5.3.04", true)));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private static HookEnvironment environment(final String profile, final boolean admitted) {
        return HookEnvironment.builder()
            .profile(profile)
            .fullRuntimeAdmission(admitted)
            .build();
    }
}
