package dev.turboism.sdk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class McpHttpConnectionTest {

    @Test
    void publicContractContainsNoAuthorizationMaterial() throws Exception {
        assertEquals(
                2,
                McpHttpConnection.class.getConstructor(URI.class, String.class).getParameterCount());
        assertFalse(Arrays.stream(McpHttpConnection.class.getMethods())
                .anyMatch(method -> method.getName().equals("authorization")));
    }

    @Test
    void acceptsCredentialFreeLoopbackEndpoint() {
        final McpHttpConnection connection =
                new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25");

        assertEquals(URI.create("http://127.0.0.1:43123/mcp"), connection.endpoint());
        assertEquals("2025-11-25", connection.protocolVersion());
        assertTrue(connection.stdioLaunch().isEmpty());
        assertEquals(
                "McpHttpConnection[endpoint=http://127.0.0.1:43123/mcp, protocolVersion=2025-11-25]",
                connection.toString());
    }

    @Test
    void carriesAnOptionalStdioLaunchDescriptor() {
        final McpStdioLaunch launch = new McpStdioLaunch(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath()
                        .toString(),
                java.util.List.of("-cp", "plugin.jar", "dev.turboism.plugin.mcp.TurboismMcpBridge", "/state"));
        final McpHttpConnection connection =
                new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", launch);

        assertEquals(launch, connection.stdioLaunch().orElseThrow());
        assertEquals(
                launch,
                new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", launch)
                        .stdioLaunch()
                        .orElseThrow());
        assertTrue(new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", null)
                .stdioLaunch()
                .isEmpty());
    }

    @Test
    void rejectsNonLoopbackOrEmbeddedCredentialEndpoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpHttpConnection(URI.create("https://example.com/mcp"), "2025-11-25"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpHttpConnection(URI.create("http://user@127.0.0.1/mcp"), "2025-11-25"));
    }
}
