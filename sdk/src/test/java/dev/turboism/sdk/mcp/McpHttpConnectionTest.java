package dev.turboism.sdk.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class McpHttpConnectionTest {

    @Test
    void publicContractContainsNoAuthorizationMaterial() throws Exception {
        assertEquals(
            2,
            McpHttpConnection.class.getConstructor(URI.class, String.class)
                .getParameterCount()
        );
        assertFalse(Arrays.stream(McpHttpConnection.class.getMethods())
            .anyMatch(method -> method.getName().equals("authorization")));
    }

    @Test
    void acceptsCredentialFreeLoopbackEndpoint() {
        final McpHttpConnection connection = new McpHttpConnection(
            URI.create("http://127.0.0.1:43123/mcp"),
            "2025-11-25"
        );

        assertEquals(URI.create("http://127.0.0.1:43123/mcp"), connection.endpoint());
        assertEquals("2025-11-25", connection.protocolVersion());
        assertEquals(
            "McpHttpConnection[endpoint=http://127.0.0.1:43123/mcp, protocolVersion=2025-11-25]",
            connection.toString()
        );
    }

    @Test
    void rejectsNonLoopbackOrEmbeddedCredentialEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new McpHttpConnection(
            URI.create("https://example.com/mcp"), "2025-11-25"
        ));
        assertThrows(IllegalArgumentException.class, () -> new McpHttpConnection(
            URI.create("http://user@127.0.0.1/mcp"), "2025-11-25"
        ));
    }
}
