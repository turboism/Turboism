package dev.turboism.plugin.demo;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.function.Consumer;

public class DemoPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.logger = context.logger();
        logger.info("DemoPlugin initialized");
    }

    @Override
    public void enable() throws Exception {
        Registration actionReg = context.actions().register("demo.hello", new ActionRegistry.Action() {
            @Override
            public String id() {
                return "demo.hello";
            }

            @Override
            public String label() {
                return "Hello Demo";
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ctx -> {};
            }
        });
        context.disposableScope().register(actionReg);

        Registration menuReg = context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Tools/Demo";
            }

            @Override
            public String actionId() {
                return "demo.hello";
            }

            @Override
            public int order() {
                return 100;
            }
        });
        context.disposableScope().register(menuReg);

        Registration mainToolbarReg = context.mainToolbar().contribute(
            new MainToolbarRegistry.MainToolbarContribution(
                "demo.toolbar",
                "demo.hello",
                "demo.toolbar.label",
                "/demo/icon.png",
                "end",
                100
            )
        );
        context.disposableScope().register(mainToolbarReg);

        Registration paletteToolbarReg = context.paletteToolbar().contribute(
            new PaletteToolbarRegistry.PaletteToolbarContribution(
                "demo.palette",
                "demo.hello",
                "demo.palette.label",
                "/demo/palette-icon.png",
                "parameters",
                "end",
                100
            )
        );
        context.disposableScope().register(paletteToolbarReg);

        Registration configReg = context.config().readScope("demo/config.json");
        context.disposableScope().register(configReg);

        Registration eventReg = context.eventBus().subscribe(DemoEvent.class, event -> {
            logger.info("DemoPlugin received event: " + event.message());
        });
        context.disposableScope().register(eventReg);
        context.eventBus().publish(new DemoEvent("DemoPlugin enabled"));

        logger.info("DemoPlugin enabled: 6 registrations enrolled in disposable scope");
    }

    @Override
    public void disable() throws Exception {
        logger.info("DemoPlugin disabled");
    }

    @Override
    public void shutdown() throws Exception {
        logger.info("DemoPlugin shutdown");
    }

    public record DemoEvent(String message) implements EventBus.TurboismEvent {}
}
