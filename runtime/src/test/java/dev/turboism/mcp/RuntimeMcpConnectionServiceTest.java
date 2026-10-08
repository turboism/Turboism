package dev.turboism.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.permission.CubismPermissionException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
                registry);

        service.publish(connection());
        assertThrows(CubismPermissionException.class, service::current);
        assertThrows(
                CubismPermissionException.class,
                () -> service.subscribe(snapshot -> {}));
        assertEquals(43123, registry.current().orElseThrow().endpoint().getPort());
    }

    @Test
    void subscribeReplaysThenRelaysChangesUnderReadPermission() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final RuntimeMcpConnectionService service = new RuntimeMcpConnectionService(
                "mcp", (permission, operation) -> {}, registry);
        final java.util.List<Optional<dev.turboism.sdk.mcp.McpHttpConnection>> seen =
                new java.util.ArrayList<>();

        final dev.turboism.sdk.plugin.Registration subscription = service.subscribe(seen::add);
        assertEquals(1, seen.size());

        final dev.turboism.sdk.plugin.Registration publication = service.publish(connection());
        publication.close();
        subscription.close();
        service.publish(connection());

        assertEquals(3, seen.size());
        assertTrue(seen.get(0).isEmpty());
        assertEquals(43123, seen.get(1).orElseThrow().endpoint().getPort());
        assertTrue(seen.get(2).isEmpty());
    }

    private static McpHttpConnection connection() {
        return new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25");
    }
}
