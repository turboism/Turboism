package dev.turboism.mcp;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Optional;

/** Permission-scoped plugin view over the process MCP connection registry. */
public final class RuntimeMcpConnectionService implements McpConnectionService {

    private final String pluginId;
    private final PermissionChecker permissions;
    private final McpConnectionRegistry registry;

    public RuntimeMcpConnectionService(
        final String pluginId,
        final PermissionChecker permissions,
        final McpConnectionRegistry registry
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<McpHttpConnection> current() {
        permissions.check(
            PermissionIds.TURBOISM_MCP_CONNECTION_READ,
            "mcp.connection.read"
        );
        return registry.current();
    }

    @Override
    public Registration publish(final McpHttpConnection connection) {
        permissions.check(
            PermissionIds.TURBOISM_MCP_CONNECTION_PUBLISH,
            "mcp.connection.publish"
        );
        return registry.publish(pluginId, Objects.requireNonNull(connection, "connection"));
    }

    private static String requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
