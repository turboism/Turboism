package dev.turboism.plugin.renderopt;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

public class RenderOptPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("RenderOptPlugin initialized");
    }

    @Override
    public void enable() {
        context.disposableScope().register(new RenderOptimizationLifecycleProvider(logger));
        logger.info("RenderOptPlugin enabled: render optimization lifecycle provider registered");
    }

    @Override
    public void disable() {
        logger.info("RenderOptPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("RenderOptPlugin shutdown");
    }

    private static final class RenderOptimizationLifecycleProvider implements AutoCloseable {

        private final PluginLogger logger;

        private RenderOptimizationLifecycleProvider(PluginLogger logger) {
            this.logger = logger;
        }

        @Override
        public void close() {
            logger.info("RenderOptPlugin render optimization lifecycle provider disposed");
        }
    }
}
