package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** Plugin lifecycle owner of the loopback MCP transport. */
public final class McpPlugin implements TurboismPlugin {

    private PluginContext context;
    private McpHttpServer server;

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
        server = McpHttpServer.start(context);
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
        if (server == null) return;
        final McpHttpServer current = server;
        server = null;
        current.close();
    }
}
