package dev.turboism.mcp;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeMcpConnectionServiceTest {

    @Test
    void independentlyChecksReadAndPublishPermissions() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final Set<String> granted = Set.of("turboism.mcp.connection.publish");
        final RuntimeMcpConnectionService service = new RuntimeMcpConnectionService(
            "mcp",
            (permission, operation) -> {
                if (!granted.contains(permission)) {
                    throw new CubismPermissionException("denied");
                }
            },
            registry
        );

        service.publish(connection());
        assertThrows(CubismPermissionException.class, service::current);
        assertEquals(43123, registry.current().orElseThrow().endpoint().getPort());
    }

    private static McpHttpConnection connection() {
        return new McpHttpConnection(
            URI.create("http://127.0.0.1:43123/mcp"),
            "2025-11-25",
            "Bearer token"
        );
    }
}
