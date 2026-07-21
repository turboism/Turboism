package dev.turboism.plugin.mesh;

import dev.turboism.plugin.mesh.service.MeshInspectorService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

/**
 * Official SDK-only plugin shell for read-only mesh inspection.
 */
public final class MeshPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private MeshInspectorService inspectorService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.inspectorService = new MeshInspectorService(context.cubismRead(), context.uiHost());
        logger.info("MeshPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(
                MeshInspectorService.INSPECT_ACTION_ID,
                "Inspect Meshes",
                ignored -> inspectorService.inspect()
            );
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("MeshPlugin enabled: mesh inspector action enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("MeshPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("MeshPlugin shutdown");
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

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("MeshPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
