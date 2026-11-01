package dev.turboism.plugin.mcp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Duration;

/** Plugin lifecycle owner of the loopback MCP transport and its local connection window. */
public final class McpPlugin extends AbstractTurboismPlugin {

    static final String CONNECTION_ACTION_ID = "turboism.mcp.connection.open";
    private static final int MENU_ORDER = 30;

    private UiScheduler uiScheduler;
    private PluginLogger logger;
    private long generation;
    private McpHttpServer server;
    private McpConnectionWindow window;

    @Override
    public synchronized void init(final PluginContext context) {
        super.init(context);
        uiScheduler = context.uiScheduler();
        logger = context.logger();
        context.disposableScope().register(this::stop);
    }

    @Override
    public synchronized void enable() {
        super.enable();
        if (server != null) return;
        final McpHttpServer started;
        try {
            started = McpHttpServer.start(context());
        } catch (IOException e) {
            throw new IllegalStateException("MCP server startup failed", e);
        }
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
            // @containment-exempt: DisposableScope.close cleans up and rethrows fatal failure with priority
            try (DisposableScope rollback = new DisposableScope()) {
                rollback.register(started);
                rollback.register(super::disable);
                rollback.register(() -> {
                    throw failure;
                });
            } catch (RuntimeException rollbackFailure) {
                throw rollbackFailure;
            } catch (Exception rollbackFailure) {
                throw new IllegalStateException("MCP startup rollback failed", rollbackFailure);
            }
        }
        context().logger().info("MCP connection metadata published");
    }

    @Override
    public void disable() throws Exception {
        stop();
    }

    @Override
    public void shutdown() throws Exception {
        try (DisposableScope cleanup = new DisposableScope()) {
            cleanup.register(super::shutdown);
            cleanup.register(this::stop);
        }
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
        final long openGeneration;
        synchronized (this) {
            if (server == null) return;
            openGeneration = generation;
        }
        if (GraphicsEnvironment.isHeadless()) {
            context().logger().warn("MCP connection window cannot open because the JVM is headless");
            return;
        }
        uiScheduler.runOnUiThread(() -> {
            final McpConnectionWindow current;
            synchronized (this) {
                if (server == null || generation != openGeneration) return;
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
                current.showAndFront();
            }
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

    private void stop() throws Exception {
        final McpHttpServer currentServer;
        final McpConnectionWindow currentWindow;
        synchronized (this) {
            generation++;
            currentServer = server;
            server = null;
            currentWindow = window;
            window = null;
        }
        // Close registrations and server before the window, without holding the EDT's lock.
        try (DisposableScope cleanup = new DisposableScope()) {
            cleanup.register(() -> disposeWindow(currentWindow));
            if (currentServer != null) cleanup.register(currentServer);
            cleanup.register(super::disable);
        }
    }

    private void disposeWindow(final McpConnectionWindow current) {
        if (current != null && !uiScheduler.runUiTeardown(current::dispose, Duration.ofSeconds(2))) {
            logger.warn("MCP connection window disposal deferred");
        }
    }
}
