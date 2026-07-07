package dev.turboism.plugin.demo;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.menu.MenuRegistry;

public class DemoPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private Registration menuRegistration;

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.logger = context.logger();
        logger.info("DemoPlugin initialized");
    }

    @Override
    public void enable() throws Exception {
        logger.info("DemoPlugin enabled");
        // Register a no-op menu contribution as a lifecycle proof.
        menuRegistration = context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() { return "Tools/Demo"; }

            @Override
            public String actionId() { return "demo.hello"; }

            @Override
            public int order() { return 100; }
        });
    }

    @Override
    public void disable() throws Exception {
        logger.info("DemoPlugin disabled");
        if (menuRegistration != null) {
            menuRegistration.close();
            menuRegistration = null;
        }
    }

    @Override
    public void shutdown() throws Exception {
        logger.info("DemoPlugin shutdown");
        disable();
    }
}
