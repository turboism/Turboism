package dev.turboism.plugin.mcp;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpConnectionWindowTest {

    @Test
    void codingAgentPromptIncludesEndpointAndCompleteAuthorizationHeader() {
        final var snapshot = new McpConnectionWindow.McpConnectionSnapshot(
            URI.create("http://127.0.0.1:43123/mcp"),
            "Bearer local-token",
            List.of()
        );

        final String prompt = McpConnectionWindow.codingAgentPrompt(
            localization("连接到 {0}，并设置 Authorization: {1}。"),
            snapshot
        );

        assertEquals(
            "连接到 http://127.0.0.1:43123/mcp，并设置 Authorization: Bearer local-token。",
            prompt
        );
        assertFalse(prompt.contains("Bearer Bearer"));
    }

    @Test
    void codingAgentPromptFallsBackToACompleteEnglishInstruction() {
        final var snapshot = new McpConnectionWindow.McpConnectionSnapshot(
            URI.create("http://127.0.0.1:43123/mcp"),
            "Bearer local-token",
            List.of()
        );

        final String prompt = McpConnectionWindow.codingAgentPrompt(
            localization("⟦prompt.coding-agent⟧"),
            snapshot
        );

        assertTrue(prompt.startsWith("This is the Turboism MCP server."));
        assertTrue(prompt.contains("http://127.0.0.1:43123/mcp"));
        assertTrue(prompt.contains("Authorization: Bearer local-token"));
        assertFalse(prompt.contains("Bearer Bearer"));
    }

    private static PluginLocalization localization(final String pattern) {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.SIMPLIFIED_CHINESE; }
            @Override public String text(final String key) { return key; }
            @Override public String format(final String key, final Object... arguments) {
                return java.text.MessageFormat.format(pattern, arguments);
            }
            @Override public boolean contains(final String key) { return true; }
        };
    }
}
