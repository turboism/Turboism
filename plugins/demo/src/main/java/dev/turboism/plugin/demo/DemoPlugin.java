package dev.turboism.plugin.demo;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.function.Consumer;

/**
 * Reference plugin exercising one contribution of each supported kind.
 *
 * <p>It exists to demonstrate and smoke-test the SDK surface, not to do anything useful: the action
 * it registers has an empty handler. Every registration made in {@code enable()} is enrolled in the
 * plugin's disposable scope, so disabling the plugin withdraws all of them without this class
 * tracking any handles itself.
 */
public class DemoPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization localization;

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.logger = context.logger();
        this.localization = context.localization();
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
                return localization.text("demo.hello.label");
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
                return localization.text("demo.menu");
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
                localization.text("demo.toolbar.label"),
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
                localization.text("demo.palette.label"),
                "/demo/palette-icon.png",
                "parameters",
                "end",
                100
            )
        );
        context.disposableScope().register(paletteToolbarReg);

        Registration contextMenuReg = context.contextMenu().contribute(
            new ContextMenuRegistry.ContextMenuContribution(
                "demo.context.hello",
                localization.text("demo.context.hello"),
                null,
                "parameter",
                5
            )
        );
        context.disposableScope().register(contextMenuReg);

        Registration configReg = context.config().readScope("demo/config.json");
        context.disposableScope().register(configReg);

        context.eventBus().publish(new DemoEvent("DemoPlugin enabled"));

        logger.info("DemoPlugin enabled: 6 registrations enrolled in disposable scope");
    }

    /** Logs a public demo event delivered through the plugin event bus. */
    @SubscribeEvent
    public void onDemoEvent(final DemoEvent event) {
        logger.info("DemoPlugin received event: " + event.message());
    }

    @Override
    public void disable() throws Exception {
        logger.info("DemoPlugin disabled");
    }

    @Override
    public void shutdown() throws Exception {
        logger.info("DemoPlugin shutdown");
    }

    /**
     * Demonstration event published on the bus when the plugin is enabled, and logged by the
     * plugin's own subscriber.
     *
     * @param message human-readable text describing why the event was published
     */
    public record DemoEvent(String message) implements EventBus.TurboismEvent {}
}
