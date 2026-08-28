package dev.turboism.sdk.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class McpHttpConnectionTest {

    @Test
    void acceptsLoopbackEndpointAndRedactsAuthorizationFromText() {
        final McpHttpConnection connection = new McpHttpConnection(
            URI.create("http://127.0.0.1:43123/mcp"),
            "2025-11-25",
            "Bearer secret-value"
        );

        assertEquals("Bearer secret-value", connection.authorization());
        assertFalse(connection.toString().contains("secret-value"));
    }

    @Test
    void rejectsNonLoopbackOrEmbeddedCredentialEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new McpHttpConnection(
            URI.create("https://example.com/mcp"), "2025-11-25", "Bearer token"
        ));
        assertThrows(IllegalArgumentException.class, () -> new McpHttpConnection(
            URI.create("http://user@127.0.0.1/mcp"), "2025-11-25", "Bearer token"
        ));
    }
}
