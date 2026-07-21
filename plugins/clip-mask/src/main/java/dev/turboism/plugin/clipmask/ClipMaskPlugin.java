package dev.turboism.plugin.clipmask;

import dev.turboism.plugin.clipmask.service.ClipMaskInspectorService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

/**
 * Official SDK-only plugin shell for read-only clip-mask inspection behavior.
 * Enable rolls back the disposable scope if any contribution fails so partial registrations do not leak.
 */
public final class ClipMaskPlugin implements TurboismPlugin {

    private static final String INSPECT_ACTION_ID = "clip-mask.inspector.inspect";
    private static final String INSPECT_ACTION_LABEL = "Inspect Clip Masks";

    private PluginContext context;
    private PluginLogger logger;
    private ClipMaskInspectorService inspectorService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.inspectorService = new ClipMaskInspectorService(context.cubismRead(), context.uiHost());
        logger.info("ClipMaskPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(INSPECT_ACTION_ID, INSPECT_ACTION_LABEL, ignored -> inspectorService.inspect());
            context.disposableScope().register(inspectorService.registerPanel());
            context.disposableScope().register(inspectorService.openInspectorDialog());
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("ClipMaskPlugin enabled: clip-mask inspector panel and dialog enrolled in disposable scope");
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ClipMaskPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    @Override
    public void disable() {
        logger.info("ClipMaskPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("ClipMaskPlugin shutdown");
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
}
