package dev.turboism.plugin.mcp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;

/** Plugin lifecycle owner of the loopback MCP transport and its local connection window. */
public final class McpPlugin extends AbstractTurboismPlugin {

    static final String CONNECTION_ACTION_ID = "turboism.mcp.connection.open";
    private static final int MENU_ORDER = 30;

    private McpHttpServer server;
    private McpConnectionWindow window;

    @Override
    public synchronized void init(final PluginContext context) throws Exception {
        super.init(context);
        context.disposableScope().register(this::disposeWindow);
    }

    @Override
    public synchronized void enable() throws Exception {
        super.enable();
        if (server != null) return;
        final McpHttpServer started = McpHttpServer.start(context());
        Registration publication = null;
        Registration action = null;
        Registration menu = null;
        try {
            publication = context()
                    .services()
                    .require(McpConnectionService.class)
                    .publish(new McpHttpConnection(
                            started.endpoint(),
                            McpProtocol.VERSION,
                            started.stdioLaunch().orElse(null)));
            disposalScope().register(publication);
            action = context().actions().register(CONNECTION_ACTION_ID, connectionAction());
            disposalScope().register(action);
            menu = context().menus().contribute(connectionMenu());
            disposalScope().register(menu);
            server = started;
        } catch (RuntimeException | Error failure) {
            try {
                super.disable();
            } catch (Exception ignored) {
            }
            started.close();
            throw failure;
        }
        context().logger().info("MCP connection metadata published");
    }

    @Override
    public synchronized void disable() throws Exception {
        stop();
    }

    @Override
    public synchronized void shutdown() throws Exception {
        stop();
        super.shutdown();
    }

    synchronized McpHttpServer serverForTests() {
        return server;
    }

    private ActionRegistry.Action connectionAction() {
        return new ActionRegistry.Action() {
            @Override
            public String id() {
                return CONNECTION_ACTION_ID;
            }

            @Override
            public String label() {
                return context().localization().text("menu.connection", "MCP Connection");
            }

            @Override
            public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> showConnectionWindow();
            }
        };
    }

    private MenuRegistry.MenuContribution connectionMenu() {
        return new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Turboism/" + context().localization().text("menu.connection", "MCP Connection");
            }

            @Override
            public String actionId() {
                return CONNECTION_ACTION_ID;
            }

            @Override
            public int order() {
                return MENU_ORDER;
            }
        };
    }

    private void showConnectionWindow() {
        if (GraphicsEnvironment.isHeadless()) {
            context().logger().warn("MCP connection window cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            final McpConnectionWindow current;
            synchronized (this) {
                if (server == null) return;
                final PluginContext ctx;
                try {
                    ctx = context();
                } catch (IllegalStateException e) {
                    return;
                }
                if (window == null) {
                    window = new McpConnectionWindow(ctx.localization(), ctx.logger());
                    window.bind(this::connectionSnapshot);
                }
                current = window;
            }
            current.showAndFront();
        });
    }

    private synchronized McpConnectionWindow.McpConnectionSnapshot connectionSnapshot() {
        final McpHttpServer current = server;
        if (current == null) {
            return new McpConnectionWindow.McpConnectionSnapshot(null, null, java.util.List.of());
        }
        return new McpConnectionWindow.McpConnectionSnapshot(
                current.endpoint(), current.stdioClientConfig(), current.connectionHistory());
    }

    private void stop() {
        try {
            super.disable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP registrations teardown failed", e);
        }
        final McpHttpServer current = server;
        server = null;
        if (current != null) current.close();
        disposeWindow();
    }

    private void disposeWindow() {
        final McpConnectionWindow current;
        synchronized (this) {
            current = window;
            window = null;
        }
        if (current == null) return;
        if (SwingUtilities.isEventDispatchThread()) current.dispose();
        else SwingUtilities.invokeLater(current::dispose);
    }
}
