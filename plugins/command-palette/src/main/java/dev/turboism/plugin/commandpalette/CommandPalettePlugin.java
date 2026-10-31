package dev.turboism.plugin.commandpalette;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Command Palette plugin: a Ctrl+K launcher over the host's supported Editor commands.
 *
 * <p>Enable registers the {@code command-palette.open} action with the declared
 * {@code Ctrl+K} default shortcut (the runtime keybinding service binds it globally and
 * the user's own binding always wins) plus a Turboism-menu entry as the non-keyboard
 * discovery path. The handler toggles the palette: the dialog is created lazily on the
 * first open and disposed on disable/shutdown. Every registration lands in the plugin
 * disposable scope, so teardown needs no bookkeeping of its own.
 */
public final class CommandPalettePlugin implements TurboismPlugin {

    static final String ACTION_ID = "command-palette.open";
    static final String ACTION_SHORTCUT = "Ctrl+K";
    static final String MENU_ROOT = "Turboism";
    static final int MENU_ORDER = 30;

    private final AtomicReference<CommandPaletteDialog> palette = new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization localization;
    private boolean initialized;
    private boolean enabled;
    private long generation;

    public CommandPalettePlugin() {}

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.localization = context.localization();
        synchronized (lifecycleLock) {
            initialized = true;
        }
        context.disposableScope().register(this::disposePalette);
        logger.info("Command Palette initialized");
    }

    @Override
    public void enable() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("Command Palette must be initialized before enable.");
            }
            if (enabled) {
                return;
            }
            enabled = true;
        }
        final Registration action = context.actions().register(ACTION_ID, openAction());
        context.disposableScope().register(action);
        final Registration menu = context.menus().contribute(menuContribution());
        context.disposableScope().register(menu);
        logger.info("Command Palette enabled");
    }

    @Override
    public void disable() {
        disposePalette();
    }

    @Override
    public void shutdown() {
        disposePalette();
    }

    /** Handles Ctrl+K / menu activation: toggles the palette on the EDT. */
    private void onOpen() {
        final long openGeneration;
        synchronized (lifecycleLock) {
            if (!enabled) return;
            openGeneration = generation;
        }
        if (GraphicsEnvironment.isHeadless()) {
            logger.warn("Command Palette cannot open because the JVM is headless");
            return;
        }
        context.uiScheduler().runOnUiThread(() -> togglePalette(openGeneration));
    }

    private void togglePalette(final long openGeneration) {
        synchronized (lifecycleLock) {
            if (!enabled || generation != openGeneration) return;
            CommandPaletteDialog dialog = palette.get();
            if (dialog == null) {
                dialog = new CommandPaletteDialog(
                        context, () -> localization.text("palette.hint", "Search commands, Enter to run"));
                palette.set(dialog);
            }
            dialog.toggle();
        }
    }

    private void disposePalette() {
        final CommandPaletteDialog dialog;
        synchronized (lifecycleLock) {
            enabled = false;
            generation++;
            dialog = palette.getAndSet(null);
        }
        // Capture the old dialog before waiting: a deferred cleanup must not touch a new enable.
        if (dialog != null && !context.uiScheduler().runUiTeardown(dialog::dispose, Duration.ofSeconds(2))) {
            logger.warn("Command Palette window disposal deferred");
        }
    }

    private ActionRegistry.Action openAction() {
        return new ActionRegistry.Action() {
            @Override
            public String id() {
                return ACTION_ID;
            }

            @Override
            public String label() {
                return localization.text("action.open-palette", "Open Command Palette");
            }

            @Override
            public java.util.Optional<String> defaultShortcut() {
                return java.util.Optional.of(ACTION_SHORTCUT);
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> onOpen();
            }
        };
    }

    private MenuRegistry.MenuContribution menuContribution() {
        return new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return MENU_ROOT + "/" + localization.text("menu.command-palette", "Command Palette");
            }

            @Override
            public String actionId() {
                return ACTION_ID;
            }

            @Override
            public int order() {
                return MENU_ORDER;
            }
        };
    }
}
