package dev.turboism.plugin.perfopt;

import dev.turboism.plugin.perfopt.b1.application.FpsPreferenceBinding;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

public final class PerfOptPlugin implements TurboismPlugin {

    private static final String FPS_OVERLAY_ACTION_ID = "perfopt.fps-overlay.toggle";
    private static final String FPS_OVERLAY_LABEL = "Toggle FPS Overlay";
    private static final String PERFORMANCE_MENU_PATH = "Tools/Performance";
    private static final int PERFORMANCE_MENU_ORDER = 200;

    private FpsPreferenceBinding b1Application = new FpsPreferenceBinding();
    private PluginContext context;
    private PluginLogger logger;
    private boolean fpsOverlayEnabled;

    @Override
    public void init(PluginContext context) {
        this.context = context;
        b1Application.init(context.config());
        this.logger = context.logger();
        this.fpsOverlayEnabled = false;
        logger.info("PerfOptPlugin initialized");
    }

    @Override
    public void enable() {
        b1Application.enable();
        Registration actionRegistration = context.actions().register(FPS_OVERLAY_ACTION_ID, fpsOverlayAction());
        context.disposableScope().register(actionRegistration);

        Registration menuRegistration = context.menus().contribute(performanceMenuContribution());
        context.disposableScope().register(menuRegistration);

        logger.info("PerfOptPlugin enabled: FPS overlay stub registered");
    }

    @Override
    public void disable() {
        b1Application.disable();
        logger.info("PerfOptPlugin disabled");
    }

    @Override
    public void shutdown() {
        b1Application.shutdown();
        logger.info("PerfOptPlugin shutdown");
    }

    boolean isFpsOverlayEnabled() {
        return fpsOverlayEnabled;
    }

    private ActionRegistry.Action fpsOverlayAction() {
        return new ActionRegistry.Action() {
            @Override
            public String id() {
                return FPS_OVERLAY_ACTION_ID;
            }

            @Override
            public String label() {
                return FPS_OVERLAY_LABEL;
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return context -> toggleFpsOverlayPlaceholder();
            }
        };
    }

    private MenuRegistry.MenuContribution performanceMenuContribution() {
        return new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return PERFORMANCE_MENU_PATH;
            }

            @Override
            public String actionId() {
                return FPS_OVERLAY_ACTION_ID;
            }

            @Override
            public int order() {
                return PERFORMANCE_MENU_ORDER;
            }
        };
    }

    private void toggleFpsOverlayPlaceholder() {
        fpsOverlayEnabled = !fpsOverlayEnabled;
        logger.info("FPS overlay placeholder " + (fpsOverlayEnabled ? "enabled" : "disabled"));
    }
}
