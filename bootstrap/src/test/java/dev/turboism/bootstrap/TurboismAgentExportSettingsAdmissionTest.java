package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the export-settings admission gate in {@link ExportSettingsHookContributor}. */
final class TurboismAgentExportSettingsAdmissionTest {

    @Test
    void exportSettingsFollowOrdinaryReviewedAdmission() {
        final ExportSettingsHookContributor contributor = new ExportSettingsHookContributor();
        assertTrue(contributor.admitted(environment("5.2.03", true)));
        assertTrue(contributor.admitted(environment("5.3.02", true)));
        assertTrue(contributor.admitted(environment("5.3.03", true)));
        assertFalse(contributor.admitted(environment("5.3.02", false)));
        assertFalse(contributor.admitted(environment("5.2.03", false)));
        assertFalse(contributor.admitted(environment("5.3.03", false)));
        assertFalse(contributor.admitted(environment("5.2.02", true)));
        assertFalse(contributor.admitted(environment("5.3.04", true)));
        assertFalse(contributor.admitted(environment("5.3", true)));
        assertFalse(contributor.admitted(environment("5.3.02.1", true)));
    }

    private static HookEnvironment environment(final String profile, final boolean admitted) {
        return HookEnvironment.builder()
            .profile(profile)
            .fullRuntimeAdmission(admitted)
            .build();
    }
}
