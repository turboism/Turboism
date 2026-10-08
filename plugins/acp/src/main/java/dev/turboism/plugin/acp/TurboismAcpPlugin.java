package dev.turboism.plugin.acp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import javax.swing.SwingUtilities;

/**
 * Official automation plugin that connects Turboism to a user-installed ACP v1 agent.
 *
 * <p>The agent runs as a supervised subprocess speaking JSON-RPC over stdio; Turboism passes its
 * authenticated loopback MCP endpoint into each session so the agent can drive Cubism through
 * typed tools. Agent installation, provider configuration, and credentials stay with each
 * external agent — this plugin never bundles runtimes or stores secrets.</p>
 */
public final class TurboismAcpPlugin implements TurboismPlugin {

    static final String OPEN_ACTION_ID = "turboism-acp.open";
    static final String SETTINGS_ACTION_ID = "turboism-acp.settings.open";
    static final String TOOLBAR_CONTRIBUTION_ID = "turboism-acp.main-toolbar";
    private static final String TOOLBAR_ICON = "icons/main-toolbar-acp.png";
    private static final String TOOLBAR_HOVER_ICON = "icons/main-toolbar-acp-hover.png";
    // AFTER entries share one semantic anchor. Installing after Core's order 10 contribution makes
    // this later insertion land immediately left of Turboism Home while retaining the host divider.
    private static final int TOOLBAR_ORDER = 11;
    private static final int SETTINGS_MENU_ORDER = 42;

    private final Runnable beforeWindowConstruction;
    private PluginContext context;
    private PluginLocalization localization;
    private AcpPluginSettings settings;
    private AcpChatWindow window;
    private AcpChatController controller;
    private Registration agentActionRegistration;
    private Registration settingsActionRegistration;
    private Registration settingsMenuRegistration;
    private Registration toolbarRegistration;
    private volatile boolean enabled;

    /** Creates the production plugin entrypoint. */
    public TurboismAcpPlugin() {
        this(() -> {});
    }

    TurboismAcpPlugin(final Runnable beforeWindowConstruction) {
        this.beforeWindowConstruction = Objects.requireNonNull(beforeWindowConstruction, "beforeWindowConstruction");
    }

    @Override
    public synchronized void init(final PluginContext context) {
        if (this.context != null) {
            throw new IllegalStateException("Turboism ACP is already initialized");
        }
        this.context = Objects.requireNonNull(context, "context");
        localization = context.localization();
        context.disposableScope().register(this::disposeUi);
        context.logger().info("Turboism ACP initialized");
    }

    @Override
    public synchronized void enable() {
        if (context == null) {
            throw new IllegalStateException("Turboism ACP must be initialized before enable");
        }
        if (enabled) return;
        Registration agentAction = null;
        Registration settingsAction = null;
        Registration settingsMenu = null;
        Registration toolbar = null;
        AcpPluginSettings enabledSettings = null;
        try {
            enabledSettings = new AcpPluginSettings(context.config(), context.logger());
            agentAction = action(OPEN_ACTION_ID, "action.open-agent", this::openAgentWindow);
            settingsAction = action(SETTINGS_ACTION_ID, "action.open-settings", this::openSettingsWindow);
            settingsMenu = context.menus().contribute(new MenuRegistry.MenuContribution() {
                @Override
                public String menuPath() {
                    return "Turboism/" + localization.text("menu.acp-settings");
                }

                @Override
                public String actionId() {
                    return SETTINGS_ACTION_ID;
                }

                @Override
                public int order() {
                    return SETTINGS_MENU_ORDER;
                }
            });
            toolbar = context.services()
                    .require(MainToolbarRegistry.class)
                    .contributeButton(new MainToolbarRegistry.MainToolbarButtonContribution(
                            TOOLBAR_CONTRIBUTION_ID,
                            OPEN_ACTION_ID,
                            "toolbar.acp.label",
                            "toolbar.acp.tooltip",
                            new MainToolbarRegistry.IconVariants(
                                    TOOLBAR_ICON,
                                    java.util.Optional.of(TOOLBAR_HOVER_ICON),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty()),
                            MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                            TOOLBAR_ORDER));
            settings = enabledSettings;
            agentActionRegistration = agentAction;
            settingsActionRegistration = settingsAction;
            settingsMenuRegistration = settingsMenu;
            toolbarRegistration = toolbar;
            enabled = true;
        } catch (RuntimeException | Error failure) {
            close(toolbar, settingsMenu, settingsAction, agentAction);
            if (enabledSettings != null) enabledSettings.close();
            throw failure;
        }
        context.logger().info("Turboism ACP enabled");
    }

    @Override
    public void disable() {
        final Registration agentAction;
        final Registration settingsAction;
        final Registration settingsMenu;
        final Registration toolbar;
        final AcpChatController currentController;
        final AcpPluginSettings currentSettings;
        synchronized (this) {
            enabled = false;
            agentAction = agentActionRegistration;
            settingsAction = settingsActionRegistration;
            settingsMenu = settingsMenuRegistration;
            toolbar = toolbarRegistration;
            currentController = controller;
            currentSettings = settings;
            agentActionRegistration = null;
            settingsActionRegistration = null;
            settingsMenuRegistration = null;
            toolbarRegistration = null;
            settings = null;
            controller = null;
        }
        close(toolbar, settingsMenu, settingsAction, agentAction);
        if (currentController != null) currentController.close();
        disposeFrames();
        if (currentSettings != null) currentSettings.close();
    }

    @Override
    public void shutdown() {
        disable();
        synchronized (this) {
            context = null;
            localization = null;
            settings = null;
        }
    }

    private Registration action(final String id, final String labelKey, final Runnable handler) {
        return context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return localization.text(labelKey);
            }

            @Override
            public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> handler.run();
            }
        });
    }

    private void openAgentWindow() {
        openWindow(WindowTarget.AGENT);
    }

    private void openSettingsWindow() {
        openWindow(WindowTarget.SETTINGS);
    }

    private void openWindow(final WindowTarget target) {
        if (!enabled) return;
        if (GraphicsEnvironment.isHeadless()) {
            context.logger().warn("Turboism ACP cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(() -> showWindow(target));
    }

    private void showWindow(final WindowTarget target) {
        beforeWindowConstruction.run();
        final AcpChatWindow toShow;
        AcpChatController autoConnect = null;
        synchronized (this) {
            if (!enabled || settings == null) return;
            if (window == null) {
                final AcpPluginSettings currentSettings = settings;
                final AcpChatWindow created = new AcpChatWindow(
                        localization,
                        currentSettings.agentId(),
                        currentSettings.customCommand(),
                        currentSettings.initialPrompt().orElse(""));
                final AcpChatController next = new AcpChatController(context, currentSettings, created);
                created.bind(
                        () -> next.connect(created.agentId(), created.customCommand(), created.initialPrompt()),
                        next::sendPrompt,
                        next::cancel,
                        next::setConfigOption,
                        next::newSession,
                        next::selectSession,
                        next::refreshSessions,
                        next::detectAgents,
                        next::authenticate,
                        next::openAgentLogin,
                        next::logout,
                        () -> next.saveSettings(created.agentId(), created.customCommand(), created.initialPrompt()));
                window = created;
                controller = next;
            }
            toShow = window;
            if (toShow != null && controller != null && target == WindowTarget.AGENT && toShow.claimAutoConnect()) {
                autoConnect = controller;
            }
        }
        if (target == WindowTarget.SETTINGS) {
            presentSettingsWindow(toShow::showSettingsAndFront);
            return;
        }
        final AcpChatController connection = autoConnect;
        presentAgentWindow(connection == null ? null : connection::connect, toShow::showAgentAndFront);
    }

    /** Starts first-open background work before the Agent frame claims foreground focus. */
    static void presentAgentWindow(final Runnable autoConnect, final Runnable showAndFocus) {
        if (autoConnect != null) autoConnect.run();
        Objects.requireNonNull(showAndFocus, "showAndFocus").run();
    }

    /** Opens pre-connection settings without touching MCP, ACP, or an agent session. */
    static void presentSettingsWindow(final Runnable showAndFocus) {
        Objects.requireNonNull(showAndFocus, "showAndFocus").run();
    }

    /**
     * Returns whether opening this target should start the first agent connection.
     *
     * <p>The Agent window connects immediately using the selected profile. The Settings window
     * remains available without starting an agent.</p>
     */
    static boolean shouldAutoConnect(final boolean agentWindow) {
        return agentWindow;
    }

    private void disposeUi() {
        final AcpChatController current;
        synchronized (this) {
            current = controller;
            controller = null;
        }
        if (current != null) current.close();
        disposeFrames();
    }

    private void disposeFrames() {
        final Runnable dispose = () -> {
            final AcpChatWindow current;
            synchronized (this) {
                current = window;
                window = null;
            }
            if (current != null) current.dispose();
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) dispose.run();
            else SwingUtilities.invokeAndWait(dispose);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (context != null) context.logger().warn("Turboism ACP window disposal was interrupted");
        } catch (InvocationTargetException | RuntimeException failure) {
            if (context != null) context.logger().warn("Turboism ACP window disposal failed");
        }
    }

    private static void close(final Registration... registrations) {
        for (Registration registration : registrations) {
            if (registration == null) continue;
            try {
                registration.close();
            } catch (RuntimeException failure) {
                // Unregistering is best-effort; plugin teardown must finish.
            }
        }
    }

    private enum WindowTarget {
        AGENT,
        SETTINGS
    }
}
