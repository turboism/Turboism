package dev.turboism.mcp;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpConnectionRegistryTest {

    @Test
    void staleRevocationCannotClearReplacementPublication() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final Registration first = registry.publish("mcp", connection(41001, "one"));
        final Registration replacement = registry.publish("mcp", connection(41002, "two"));

        first.close();

        assertEquals(41002, registry.current().orElseThrow().endpoint().getPort());
        replacement.close();
        assertTrue(registry.current().isEmpty());
    }

    @Test
    void rejectsPublicationAfterTerminalClose() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.publish("mcp", connection(41001, "one"));

        registry.close();

        assertTrue(registry.current().isEmpty());
        assertThrows(
            IllegalStateException.class,
            () -> registry.publish("mcp", connection(41002, "two"))
        );
        assertTrue(registry.current().isEmpty());
    }

    @Test
    void rejectsPublicationFromAnotherPlugin() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.publish("mcp", connection(41001, "one"));

        assertThrows(
            IllegalStateException.class,
            () -> registry.publish("other", connection(41002, "two"))
        );
    }

    private static McpHttpConnection connection(final int port, final String token) {
        return new McpHttpConnection(
            URI.create("http://127.0.0.1:" + port + "/mcp"),
            "2025-11-25",
            "Bearer " + token
        );
    }
}
