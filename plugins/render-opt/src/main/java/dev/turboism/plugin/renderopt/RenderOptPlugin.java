package dev.turboism.plugin.renderopt;

import dev.turboism.plugin.renderopt.b1.application.RenderPreferenceBinding;
import dev.turboism.plugin.renderopt.service.RenderStatusOverlayService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

public class RenderOptPlugin implements TurboismPlugin {

    private static final String REFRESH_ACTION_ID = "render-status.overlay.refresh";
    private static final String REFRESH_ACTION_LABEL = "Refresh Render Status Overlay";

    private RenderPreferenceBinding b1Application = new RenderPreferenceBinding();
    private PluginContext context;
    private PluginLogger logger;
    private RenderStatusOverlayService renderStatusOverlayService;

    @Override
    public void init(PluginContext context) {
        this.context = context;
        b1Application.init(context.config());
        this.logger = context.logger();
        this.renderStatusOverlayService = new RenderStatusOverlayService(context.cubismRead(), context.uiHost());
        logger.info("RenderOptPlugin initialized");
    }

    @Override
    public void enable() {
        b1Application.enable();
        registerAction(REFRESH_ACTION_ID, REFRESH_ACTION_LABEL, ignored -> renderStatusOverlayService.refreshStatus());
        context.disposableScope().register(renderStatusOverlayService.registerOverlay());
        context.disposableScope().register(new RenderOptimizationLifecycleProvider(logger));
        logger.info("RenderOptPlugin enabled: render status overlay contribution enrolled in disposable scope");
    }

    @Override
    public void disable() {
        b1Application.disable();
        logger.info("RenderOptPlugin disabled");
    }

    @Override
    public void shutdown() {
        b1Application.shutdown();
        logger.info("RenderOptPlugin shutdown");
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
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
