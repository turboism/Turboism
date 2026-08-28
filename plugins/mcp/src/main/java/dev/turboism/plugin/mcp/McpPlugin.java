package dev.turboism.plugin.mcp;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** Plugin lifecycle owner of the loopback MCP transport. */
public final class McpPlugin implements TurboismPlugin {

    private PluginContext context;
    private McpHttpServer server;
    private Registration connectionPublication;

    @Override
    public synchronized void init(final PluginContext context) {
        if (this.context != null) {
            throw new IllegalStateException("Turboism MCP plugin is already initialized");
        }
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public synchronized void enable() throws Exception {
        if (server != null) return;
        if (context == null) {
            throw new IllegalStateException("Turboism MCP plugin was not initialized");
        }
        final McpHttpServer started = McpHttpServer.start(context);
        try {
            connectionPublication = context.mcpConnections().publish(new McpHttpConnection(
                started.endpoint(),
                McpProtocol.VERSION,
                started.authorization()
            ));
            server = started;
        } catch (RuntimeException | Error failure) {
            started.close();
            throw failure;
        }
        context.logger().info(
            "MCP connection metadata written to " + server.connectionFile()
        );
    }

    @Override
    public synchronized void disable() {
        stop();
    }

    @Override
    public synchronized void shutdown() {
        stop();
    }

    synchronized McpHttpServer serverForTests() {
        return server;
    }

    private void stop() {
        final Registration publication = connectionPublication;
        connectionPublication = null;
        final McpHttpServer current = server;
        server = null;
        try {
            if (publication != null) publication.close();
        } finally {
            if (current != null) current.close();
        }
    }
}
