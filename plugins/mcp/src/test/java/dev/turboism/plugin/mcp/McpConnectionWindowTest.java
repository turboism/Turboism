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

    private static final String STDIO_CONFIG = "{\"command\":\"java\",\"args\":[\"/bridge.java\"]}";

    @Test
    void codingAgentPromptCarriesEndpointAndStdioConfig() {
        final var snapshot = new McpConnectionWindow.McpConnectionSnapshot(
            URI.create("http://127.0.0.1:43123/mcp"),
            STDIO_CONFIG,
            List.of()
        );

        final String prompt = McpConnectionWindow.codingAgentPrompt(
            localization("端点 {0} 配置 {1}"),
            snapshot
        );

        assertEquals(
            "端点 http://127.0.0.1:43123/mcp 配置 " + STDIO_CONFIG,
            prompt
        );
    }

    @Test
    void codingAgentPromptFallsBackToACompleteEnglishInstruction() {
        final var snapshot = new McpConnectionWindow.McpConnectionSnapshot(
            URI.create("http://127.0.0.1:43123/mcp"),
            STDIO_CONFIG,
            List.of()
        );

        final String prompt = McpConnectionWindow.codingAgentPrompt(
            localization("⟦prompt.coding-agent⟧"),
            snapshot
        );

        assertTrue(prompt.startsWith("This is the Turboism MCP server."));
        assertTrue(prompt.contains("http://127.0.0.1:43123/mcp"));
        assertTrue(prompt.contains(STDIO_CONFIG));
        assertTrue(prompt.contains("Bearer"));
        assertFalse(prompt.contains("mcp.token value"));
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
