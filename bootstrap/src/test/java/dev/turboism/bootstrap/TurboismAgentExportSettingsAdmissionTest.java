package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the export-settings admission gate in {@link TurboismAgent}. */
class TurboismAgentExportSettingsAdmissionTest {

    @Test
    void exportSettingsFollowOrdinaryReviewedAdmission() {
        assertTrue(TurboismAgent.exportSettingsRuntimeAdmitted("5.3.02", true));
        assertFalse(TurboismAgent.exportSettingsRuntimeAdmitted("5.3.02", false));
        assertFalse(TurboismAgent.exportSettingsRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.exportSettingsRuntimeAdmitted("5.2.03", true));
    }
}
