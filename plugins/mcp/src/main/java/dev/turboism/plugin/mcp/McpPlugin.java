package dev.turboism.plugin.mcp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Objects;

/** Plugin lifecycle owner of the loopback MCP transport and its local connection window. */
public final class McpPlugin implements TurboismPlugin {

    static final String CONNECTION_ACTION_ID = "turboism.mcp.connection.open";
    private static final int MENU_ORDER = 30;

    private PluginContext context;
    private McpHttpServer server;
    private McpConnectionWindow window;
    private Registration connectionPublication;
    private Registration actionRegistration;
    private Registration menuRegistration;

    @Override
    public synchronized void init(final PluginContext context) {
        if (this.context != null) {
            throw new IllegalStateException("Turboism MCP plugin is already initialized");
        }
        this.context = Objects.requireNonNull(context, "context");
        context.disposableScope().register(this::disposeWindow);
    }

    @Override
    public synchronized void enable() throws Exception {
        if (server != null) return;
        if (context == null) {
            throw new IllegalStateException("Turboism MCP plugin was not initialized");
        }
        final McpHttpServer started = McpHttpServer.start(context);
        Registration publication = null;
        Registration action = null;
        Registration menu = null;
        try {
            publication = context.mcpConnections().publish(new McpHttpConnection(
                started.endpoint(),
                McpProtocol.VERSION,
                started.authorization()
            ));
            action = context.actions().register(CONNECTION_ACTION_ID, connectionAction());
            menu = context.menus().contribute(connectionMenu());
            connectionPublication = publication;
            actionRegistration = action;
            menuRegistration = menu;
            server = started;
        } catch (RuntimeException | Error failure) {
            close(menu, action, publication);
            started.close();
            throw failure;
        }
        context.logger().info("MCP connection metadata published");
    }

    @Override
    public synchronized void disable() {
        stop();
    }

    @Override
    public synchronized void shutdown() {
        stop();
        context = null;
    }

    synchronized McpHttpServer serverForTests() {
        return server;
    }

    private ActionRegistry.Action connectionAction() {
        return new ActionRegistry.Action() {
            @Override public String id() { return CONNECTION_ACTION_ID; }
            @Override public String label() {
                return text("menu.connection", "MCP Connection");
            }
            @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> showConnectionWindow();
            }
        };
    }

    private MenuRegistry.MenuContribution connectionMenu() {
        return new MenuRegistry.MenuContribution() {
            @Override public String menuPath() {
                return "Turboism/" + text("menu.connection", "MCP Connection");
            }
            @Override public String actionId() { return CONNECTION_ACTION_ID; }
            @Override public int order() { return MENU_ORDER; }
        };
    }

    private void showConnectionWindow() {
        if (GraphicsEnvironment.isHeadless()) {
            context.logger().warn("MCP connection window cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            final McpConnectionWindow current;
            synchronized (this) {
                if (server == null || context == null) return;
                if (window == null) {
                    window = new McpConnectionWindow(context.localization(), context.logger());
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
            return new McpConnectionWindow.McpConnectionSnapshot(null, "", java.util.List.of());
        }
        return new McpConnectionWindow.McpConnectionSnapshot(
            current.endpoint(),
            current.authorization(),
            current.connectionHistory()
        );
    }

    private String text(final String key, final String fallback) {
        try {
            final String value = context.localization().text(key);
            return value == null || value.isBlank() || key.equals(value) ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private void stop() {
        final Registration publication = connectionPublication;
        final Registration action = actionRegistration;
        final Registration menu = menuRegistration;
        connectionPublication = null;
        actionRegistration = null;
        menuRegistration = null;
        final McpHttpServer current = server;
        server = null;
        close(menu, action, publication);
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

    private static void close(final Registration... registrations) {
        for (Registration registration : registrations) {
            if (registration != null) registration.close();
        }
    }
}
