package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EditProtocolValidationHostCloseTest {
    @Test
    void closeRequiresExplicitAutomationOptIn() throws Exception {
        assertEquals("SKIPPED:automation-disabled",
            EditProtocolValidationHostClose.request(false, true, true, "edit-protocol-task", "5302"));
    }

    @Test
    void disabledProbeCannotCloseAnyWindow() throws Exception {
        assertEquals("SKIPPED:probe-not-running",
            EditProtocolValidationHostClose.request(true, false, true, "edit-protocol-task", "5302"));
    }

    @Test
    void terminalEvidenceMustPrecedeClose() throws Exception {
        assertEquals("SKIPPED:terminal-summary-not-written",
            EditProtocolValidationHostClose.request(true, true, false, "edit-protocol-task", "5302"));
    }

    @Test
    void invalidTaskIdentityIsRejectedWithoutDesktopAccess() throws Exception {
        assertEquals("SKIPPED:missing-or-invalid-task-run-id",
            EditProtocolValidationHostClose.request(true, true, true, "../unrelated", "5302"));
    }
}
