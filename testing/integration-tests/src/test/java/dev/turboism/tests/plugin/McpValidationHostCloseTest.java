package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class McpValidationHostCloseTest {
    @Test
    void closeRequiresExplicitAutomationOptIn() throws Exception {
        assertEquals("SKIPPED:automation-disabled",
            McpValidationHostClose.request(false, true, true, "mcp-task", "5302"));
    }

    @Test
    void disabledProbeCannotCloseAnyWindow() throws Exception {
        assertEquals("SKIPPED:probe-not-running",
            McpValidationHostClose.request(true, false, true, "mcp-task", "5302"));
    }

    @Test
    void terminalEvidenceMustPrecedeClose() throws Exception {
        assertEquals("SKIPPED:terminal-summary-not-written",
            McpValidationHostClose.request(true, true, false, "mcp-task", "5302"));
    }

    @Test
    void invalidTaskIdentityIsRejectedWithoutDesktopAccess() throws Exception {
        assertEquals("SKIPPED:missing-or-invalid-task-run-id",
            McpValidationHostClose.request(true, true, true, "../unrelated", "5302"));
    }
}
