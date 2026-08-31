package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * Official automation plugin that controls Turboism's managed fx runtime through ACP v1.
 *
 * <p>Release packaging installs the reviewed platform payload outside the plugin JAR, where this
 * plugin verifies its pinned size and SHA-256 before launch. A custom executable remains an advanced
 * override. Provider/model choices, credentials, and durable conversation data remain fx-owned.
 * Platforms without a reviewed payload fail closed rather than falling back to an unrelated
 * executable on {@code PATH}.</p>
 */
public final class TurboismWithFxPlugin implements TurboismPlugin {

    static final String OPEN_ACTION_ID = "turboism-with-fx.open";
    static final String SETTINGS_ACTION_ID = "turboism-with-fx.settings.open";
    static final String TOOLBAR_CONTRIBUTION_ID = "turboism-with-fx.main-toolbar";
    private static final String TOOLBAR_ICON = "icons/main-toolbar-fx.png";
    private static final String TOOLBAR_HOVER_ICON = "icons/main-toolbar-fx-hover.png";
    // AFTER entries share one semantic anchor. Installing after Core's order 10 contribution makes
    // this later insertion land immediately left of Turboism Home while retaining the host divider.
    private static final int TOOLBAR_ORDER = 11;
    private static final int SETTINGS_MENU_ORDER = 42;

    private final Runnable beforeWindowConstruction;
    private PluginContext context;
    private PluginLocalization localization;
    private FxPluginSettings settings;
    private TurboismWithFxWindow window;
    private TurboismWithFxController controller;
    private Registration agentActionRegistration;
    private Registration settingsActionRegistration;
    private Registration settingsMenuRegistration;
    private Registration toolbarRegistration;
    private volatile boolean enabled;

    /** Creates the production plugin entrypoint. */
    public TurboismWithFxPlugin() {
        this(() -> { });
    }

    TurboismWithFxPlugin(final Runnable beforeWindowConstruction) {
        this.beforeWindowConstruction = Objects.requireNonNull(
            beforeWindowConstruction,
            "beforeWindowConstruction"
        );
    }

    @Override
    public synchronized void init(final PluginContext context) {
        if (this.context != null) {
            throw new IllegalStateException("Turboism with fx is already initialized");
        }
        this.context = Objects.requireNonNull(context, "context");
        localization = context.localization();
        context.disposableScope().register(this::disposeUi);
        context.logger().info("Turboism with fx initialized");
    }

    @Override
    public synchronized void enable() {
        if (context == null) {
            throw new IllegalStateException("Turboism with fx must be initialized before enable");
        }
        if (enabled) return;
        Registration agentAction = null;
        Registration settingsAction = null;
        Registration settingsMenu = null;
        Registration toolbar = null;
        FxPluginSettings enabledSettings = null;
        try {
            enabledSettings = new FxPluginSettings(
                context.config(),
                context.logger(),
                FxSecretStore.create(context.paths(), context.logger())
            );
            agentAction = action(OPEN_ACTION_ID, "action.open-agent", this::openAgentWindow);
            settingsAction = action(
                SETTINGS_ACTION_ID,
                "action.open-settings",
                this::openSettingsWindow
            );
            settingsMenu = context.menus().contribute(new MenuRegistry.MenuContribution() {
                @Override public String menuPath() {
                    return "Turboism/" + localization.text("menu.fx-settings");
                }
                @Override public String actionId() { return SETTINGS_ACTION_ID; }
                @Override public int order() { return SETTINGS_MENU_ORDER; }
            });
            toolbar = context.mainToolbar().contributeButton(
                new MainToolbarRegistry.MainToolbarButtonContribution(
                    TOOLBAR_CONTRIBUTION_ID,
                    OPEN_ACTION_ID,
                    "toolbar.fx.label",
                    "toolbar.fx.tooltip",
                    new MainToolbarRegistry.IconVariants(
                        TOOLBAR_ICON,
                        java.util.Optional.of(TOOLBAR_HOVER_ICON),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty()
                    ),
                    MainToolbarRegistry.Placement.after(
                        MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
                    ),
                    TOOLBAR_ORDER
                )
            );
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
        context.logger().info("Turboism with fx enabled");
    }

    @Override
    public void disable() {
        final Registration agentAction;
        final Registration settingsAction;
        final Registration settingsMenu;
        final Registration toolbar;
        final FxPluginSettings currentSettings;
        final TurboismWithFxController currentController;
        synchronized (this) {
            enabled = false;
            agentAction = agentActionRegistration;
            settingsAction = settingsActionRegistration;
            settingsMenu = settingsMenuRegistration;
            toolbar = toolbarRegistration;
            currentSettings = settings;
            currentController = controller;
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

    private Registration action(
        final String id,
        final String labelKey,
        final Runnable handler
    ) {
        return context.actions().register(id, new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return localization.text(labelKey); }
            @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
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
            context.logger().warn("Turboism with fx cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(() -> showWindow(target));
    }

    private void showWindow(final WindowTarget target) {
        beforeWindowConstruction.run();
        final TurboismWithFxWindow toShow;
        TurboismWithFxController autoConnect = null;
        String autoExecutable = null;
        String autoInitialPrompt = null;
        synchronized (this) {
            if (!enabled || settings == null) return;
            if (window == null) {
                final FxPluginSettings currentSettings = settings;
                final TurboismWithFxWindow created = new TurboismWithFxWindow(
                    localization,
                    currentSettings.executable(),
                    currentSettings.compatibilityMode(),
                    currentSettings.initialPrompt(),
                    currentSettings.providerConfiguration()
                );
                final TurboismWithFxController next = new TurboismWithFxController(
                    context,
                    currentSettings,
                    created
                );
                created.bind(
                    () -> next.connect(
                        created.executable(),
                        created.compatibilityMode(),
                        created.initialPrompt(),
                        created.providerConfiguration()
                    ),
                    next::sendPrompt,
                    next::cancel,
                    next::setConfigOption,
                    next::newSession,
                    next::selectSession,
                    next::refreshSessions,
                    () -> next.repairManagedRuntime(created.executable()),
                    action -> next.openInteractiveFx(created.executable(), action),
                    next::discoverProviderModels,
                    () -> next.saveSettings(
                        created.executable(),
                        created.compatibilityMode(),
                        created.initialPrompt(),
                        created.providerConfiguration()
                    )
                );
                window = created;
                controller = next;
                if (shouldAutoConnect(
                    target == WindowTarget.AGENT,
                    currentSettings.compatibilityMode()
                )) {
                    autoConnect = next;
                    autoExecutable = currentSettings.executable();
                    autoInitialPrompt = currentSettings.initialPrompt();
                }
            }
            toShow = window;
        }
        if (target == WindowTarget.SETTINGS) {
            presentSettingsWindow(toShow::showSettingsAndFront);
            return;
        }
        final TurboismWithFxController connection = autoConnect;
        final String connectionExecutable = autoExecutable;
        final String connectionInitialPrompt = autoInitialPrompt;
        presentAgentWindow(
            connection == null ? null : () -> connection.connect(
                connectionExecutable,
                true,
                connectionInitialPrompt
            ),
            toShow::showAgentAndFront
        );
    }

    /** Starts first-open background work before the Agent frame claims foreground focus. */
    static void presentAgentWindow(
        final Runnable autoConnect,
        final Runnable showAndFocus
    ) {
        if (autoConnect != null) autoConnect.run();
        Objects.requireNonNull(showAndFocus, "showAndFocus").run();
    }

    /** Opens pre-connection settings without touching MCP, ACP, or an fx session. */
    static void presentSettingsWindow(final Runnable showAndFocus) {
        Objects.requireNonNull(showAndFocus, "showAndFocus").run();
    }

    /**
     * Returns whether the saved launch state is sufficient for first-open connection.
     *
     * <p>The executable no longer gates automatic connection: a blank advanced override selects the
     * managed runtime. Connection remains fail-closed until the user has acknowledged stock fx
     * native-tool compatibility mode. Reopening an already constructed window does not evaluate this
     * predicate again, so it cannot start a second ACP process.</p>
     */
    static boolean shouldAutoConnect(final boolean compatibilityMode) {
        return shouldAutoConnect(true, compatibilityMode);
    }

    static boolean shouldAutoConnect(
        final boolean agentWindow,
        final boolean compatibilityMode
    ) {
        return agentWindow && compatibilityMode;
    }

    private void disposeUi() {
        final TurboismWithFxController current;
        synchronized (this) {
            current = controller;
            controller = null;
        }
        if (current != null) current.close();
        disposeFrames();
    }

    private void disposeFrames() {
        final Runnable dispose = () -> {
            final TurboismWithFxWindow current;
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
            if (context != null) context.logger().warn("Turboism with fx window disposal was interrupted");
        } catch (InvocationTargetException | RuntimeException failure) {
            if (context != null) context.logger().error("Turboism with fx window disposal failed", failure);
        }
    }

    private enum WindowTarget {
        AGENT,
        SETTINGS
    }

    private static void close(final Registration... registrations) {
        for (Registration registration : registrations) {
            if (registration != null) registration.close();
        }
    }
}
