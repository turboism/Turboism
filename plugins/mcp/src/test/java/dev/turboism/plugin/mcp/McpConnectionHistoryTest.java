package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpConnectionHistoryTest {

    @Test
    void retainsOnlyTheNewestBoundedEntries() {
        final McpConnectionHistory history = new McpConnectionHistory();
        for (int index = 0; index < McpConnectionHistory.MAX_ENTRIES + 5; index++) {
            history.record(
                McpConnectionHistory.Event.REQUEST,
                "client-" + index,
                "method-" + index
            );
        }

        final java.util.List<McpConnectionHistory.Entry> entries = history.snapshot();
        assertEquals(McpConnectionHistory.MAX_ENTRIES, entries.size());
        assertEquals("client-5", entries.get(0).client());
        assertEquals("method-204", entries.get(entries.size() - 1).detail());
    }

    @Test
    void flattensAndCapsVisibleValuesWithoutAddingSecrets() {
        final McpConnectionHistory history = new McpConnectionHistory();
        final String bearer = "Bearer secret-token-0123456789";
        final String sessionId = "session-secret-0123456789";
        history.record(
            McpConnectionHistory.Event.SESSION_CREATED,
            "  trusted\nclient  ",
            "x".repeat(200)
        );

        final McpConnectionHistory.Entry entry = history.snapshot().get(0);
        assertEquals("trusted client", entry.client());
        assertEquals(160, entry.detail().length());
        final String visible = entry.client() + " " + entry.detail();
        assertFalse(visible.contains(bearer));
        assertFalse(visible.contains(sessionId));
        assertTrue(entry.timestamp() != null);
    }
}
