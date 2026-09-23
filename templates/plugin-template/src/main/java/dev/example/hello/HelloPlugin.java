package dev.example.hello;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

/**
 * Minimal Turboism plugin: registers one action and subscribes to one typed event.
 *
 * <p>Every registration is enrolled in the plugin's disposable scope, so disabling
 * the plugin withdraws all of them automatically. Rename the package, the plugin id
 * in {@code META-INF/turboism/plugin.json}, and the action ids together.
 */
public class HelloPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.logger = context.logger();
        logger.info("HelloPlugin initialized");
    }

    @Override
    public void enable() throws Exception {
        Registration action = context.actions().register("hello.hello", new ActionRegistry.Action() {
            @Override
            public String id() {
                return "hello.hello";
            }

            @Override
            public String label() {
                return context.localization().text("hello.action.label");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ctx -> context.eventBus().publish(new HelloEvent("Hello action invoked"));
            }
        });
        context.disposableScope().register(action);

        Registration subscription = context.eventBus().subscribe(
            HelloEvent.class,
            event -> logger.info("HelloPlugin received event: " + event.message())
        );
        context.disposableScope().register(subscription);

        logger.info("HelloPlugin enabled");
    }

    @Override
    public void disable() throws Exception {
        logger.info("HelloPlugin disabled");
    }

    @Override
    public void shutdown() throws Exception {
        logger.info("HelloPlugin shutdown");
    }

    /** Example event published by the hello action and logged by the subscriber. */
    public record HelloEvent(String message) implements TurboismEvent {
    }
}
