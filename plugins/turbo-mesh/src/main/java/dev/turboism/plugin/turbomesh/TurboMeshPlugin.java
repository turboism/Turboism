package dev.turboism.plugin.turbomesh;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.Set;

/**
 * Turbo Mesh: custom ArtMesh point placement — curvature-aware boundary resampling, autonomous
 * region subdivision, interior placement and constrained Delaunay triangulation, written back
 * through {@code Drawable.replaceGeometry} as a single undoable edit.
 */
public final class TurboMeshPlugin implements TurboismPlugin {

    public static final String GENERATE_ACTION_ID = "turbo-mesh.generate";
    public static final String APPLY_ACTION_ID = "turbo-mesh.apply";

    private PluginContext context;
    private PluginLogger logger;
    private TurboMeshPlacement placement;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.placement = new TurboMeshPlacement(context);
        logger.info("TurboMeshPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            // 1. Generate (Incremental Refinement) - Primary Action
            final Registration generateAction = context.actions()
                    .register(
                            GENERATE_ACTION_ID,
                            ActionRegistry.Action.of(
                                    GENERATE_ACTION_ID,
                                    context.localization().text("turbo-mesh.action.generate"),
                                    placement::generate));
            context.disposableScope().register(generateAction);

            // 2. Trace (Re-mesh Outline) - Secondary / Advanced Action
            final Registration applyAction = context.actions()
                    .register(
                            APPLY_ACTION_ID,
                            ActionRegistry.Action.of(
                                    APPLY_ACTION_ID,
                                    context.localization().text("turbo-mesh.action.apply"),
                                    placement::apply));
            context.disposableScope().register(applyAction);

            contributeMenus(ContextMenuRegistry.Location.PART_TAB, 105);
            contributeMenus(ContextMenuRegistry.Location.DEFORMER_TAB, 105);
            contributeMenus(ContextMenuRegistry.Location.WORKSPACE_OBJECT, 105);
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("TurboMeshPlugin enabled");
    }

    @Override
    public void disable() {
        logger.info("TurboMeshPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("TurboMeshPlugin shutdown");
    }

    private void contributeMenus(final ContextMenuRegistry.Location location, final int basePriority) {
        context.disposableScope()
                .register(context.services()
                        .find(ContextMenuRegistry.class)
                        .orElse(ContextMenuRegistry.unavailable())
                        .contribute(new ContextMenuRegistry.ContextMenuContribution(
                                "turbo-mesh.context.generate",
                                GENERATE_ACTION_ID,
                                context.localization().text("turbo-mesh.action.generate"),
                                null,
                                location,
                                Set.of(ContextMenuRegistry.ObjectKind.ART_MESH),
                                basePriority)));

        context.disposableScope()
                .register(context.services()
                        .find(ContextMenuRegistry.class)
                        .orElse(ContextMenuRegistry.unavailable())
                        .contribute(new ContextMenuRegistry.ContextMenuContribution(
                                "turbo-mesh.context.apply",
                                APPLY_ACTION_ID,
                                context.localization().text("turbo-mesh.action.apply"),
                                null,
                                location,
                                Set.of(ContextMenuRegistry.ObjectKind.ART_MESH),
                                basePriority + 5)));
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("TurboMeshPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
