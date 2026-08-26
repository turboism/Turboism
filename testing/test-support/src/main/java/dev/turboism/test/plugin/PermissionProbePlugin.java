package dev.turboism.test.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A plugin probe that exercises SDK registries during enable and records lifecycle events
 * and any failures (including permission exceptions) for test assertions.
 */
public class PermissionProbePlugin implements TurboismPlugin {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    private final boolean shouldFailEnable;

    private PluginContext context;
    private DisposableScope disposableScope;
    private int actionRegistrationCount;
    private int menuRegistrationCount;
    private int mainToolbarRegistrationCount;
    private int paletteToolbarRegistrationCount;
    private int contextMenuRegistrationCount;
    private int configRegistrationCount;
    private int eventSubscriptionCount;

    public PermissionProbePlugin() {
        this(false);
    }

    public PermissionProbePlugin(boolean shouldFailEnable) {
        this.shouldFailEnable = shouldFailEnable;
    }

    @Override
    public void init(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        events.add("init");
    }

    @Override
    public void enable() throws Exception {
        events.add("enable");
        disposableScope = context.disposableScope();

        tryRegister("action", () -> {
            Registration registration = context.actions().register(
                "probe.action",
                new ActionRegistry.Action() {
                    @Override
                    public String id() {
                        return "probe.action";
                    }

                    @Override
                    public String label() {
                        return "Probe Action";
                    }

                    @Override
                    public Consumer<ActionRegistry.ActionContext> handler() {
                        return ctx -> {
                        };
                    }
                }
            );
            disposableScope.register(registration);
            actionRegistrationCount++;
        });

        tryRegister("menu", () -> {
            Registration registration = context.menus().contribute(
                new MenuRegistry.MenuContribution() {
                    @Override
                    public String menuPath() {
                        return "Probe";
                    }

                    @Override
                    public String actionId() {
                        return "probe.action";
                    }

                    @Override
                    public int order() {
                        return 100;
                    }
                }
            );
            disposableScope.register(registration);
            menuRegistrationCount++;
        });

        tryRegister("mainToolbar", () -> {
            Registration registration = context.mainToolbar().contribute(
                new MainToolbarRegistry.MainToolbarContribution(
                    "probe.toolbar",
                    "probe.action",
                    "probe.toolbar.label",
                    "/probe/icon.png",
                    "end",
                    100
                )
            );
            disposableScope.register(registration);
            mainToolbarRegistrationCount++;
        });

        tryRegister("paletteToolbar", () -> {
            Registration registration = context.paletteToolbar().contribute(
                new PaletteToolbarRegistry.PaletteToolbarContribution(
                    "probe.palette",
                    "probe.action",
                    "probe.palette.label",
                    "/probe/palette-icon.png",
                    "parameters",
                    "end",
                    100
                )
            );
            disposableScope.register(registration);
            paletteToolbarRegistrationCount++;
        });

        tryRegister("contextMenu", () -> {
            Registration registration = context.contextMenu().contribute(
                new ContextMenuRegistry.ContextMenuContribution(
                    "probe.context",
                    "Probe Context",
                    null,
                    "parameter",
                    100
                )
            );
            disposableScope.register(registration);
            contextMenuRegistrationCount++;
        });

        tryRegister("config", () -> {
            Registration registration = context.config().writeScope("probe/config.json");
            disposableScope.register(registration);
            configRegistrationCount++;
        });

        tryRegister("event", () -> {
            Registration registration = context.eventBus().subscribe(ProbeEvent.class, event -> {
            });
            disposableScope.register(registration);
            eventSubscriptionCount++;
        });

        if (shouldFailEnable) {
            disposableScope.close();
            throw new RuntimeException("enable failed as requested");
        }
    }

    @Override
    public void disable() throws Exception {
        events.add("disable");
        if (disposableScope != null) {
            disposableScope.close();
        }
    }

    @Override
    public void shutdown() {
        events.add("shutdown");
        if (disposableScope != null) {
            try {
                disposableScope.close();
            } catch (Exception e) {
                failures.add(e);
            }
        }
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    public int actionRegistrationCount() {
        return actionRegistrationCount;
    }

    public int menuRegistrationCount() {
        return menuRegistrationCount;
    }

    public int mainToolbarRegistrationCount() {
        return mainToolbarRegistrationCount;
    }

    public int paletteToolbarRegistrationCount() {
        return paletteToolbarRegistrationCount;
    }

    public int contextMenuRegistrationCount() {
        return contextMenuRegistrationCount;
    }

    public int configRegistrationCount() {
        return configRegistrationCount;
    }

    public int eventSubscriptionCount() {
        return eventSubscriptionCount;
    }

    private void tryRegister(String name, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    public record ProbeEvent(String message) implements EventBus.TurboismEvent {}
}
