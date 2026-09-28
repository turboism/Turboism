package dev.turboism.plugin.mesheditmirroraxisenhance;

import dev.turboism.plugin.mesheditmirroraxisenhance.service.MeshInspectorService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Set;
import java.util.function.Consumer;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.ui.UiHostCapabilityService;

/**
 * Official SDK-only plugin shell for read-only mesh inspection.
 */
public final class MeshEditMirrorAxisEnhancePlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private MeshInspectorService inspectorService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.inspectorService = new MeshInspectorService(context.cubismRead(), context.services().get(UiHostCapabilityService.class));
        logger.info("MeshEditMirrorAxisEnhancePlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(
                MeshInspectorService.INSPECT_ACTION_ID,
                "Inspect Meshes",
                ignored -> inspectorService.inspect()
            );
            registerMirrorLinkedDeletion();
            context.disposableScope().register(
                context.services().get(MeshMirrorMoveParticipation.class).participate()
            );
            context.disposableScope().register(
                context.services().get(MeshMirrorToolEligibility.class).extendEligibleTools(Set.of(
                    MeshEditTool.ARROW,
                    MeshEditTool.ERASER,
                    MeshEditTool.LASSO
                ))
            );
            context.disposableScope().register(
                context.services().get(MeshEditUiService.class).contributeMirrorAxisAngleControl(
                    new MeshEditUiService.MirrorAxisAngleControl(
                        "mesh.mirror-axis.angle",
                        context.localization().text("mesh.mirror-axis.angle.label"),
                        context.localization().text("mesh.mirror-axis.angle.reset"),
                        -180.0f,
                        180.0f,
                        0.1f,
                        this::setMirrorAxisAngleDegrees
                    )
                )
            );
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("MeshEditMirrorAxisEnhancePlugin enabled: mesh inspector action enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("MeshEditMirrorAxisEnhancePlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("MeshEditMirrorAxisEnhancePlugin shutdown");
    }

    /** Called by the native-position mesh-edit control. */
    public void setMirrorAxisAngleDegrees(final float angleDegrees) {
        context.services().get(MeshMirrorAxisService.class).setCurrentAngleDegrees(angleDegrees);
    }

    /**
     * Deletes the mirror counterparts alongside whatever the host is deleting.
     *
     * <p>Cubism does this natively from 5.3.02. On hosts that do not, the framework intercepts
     * the deletion and asks here; on hosts that do, it never intercepts, so this is simply never
     * called and the behaviour cannot be applied twice.</p>
     *
     * <p>The enable condition is the host's own mirror toggle, reported through the deletion,
     * rather than anything this plugin invents.</p>
     */
    private void registerMirrorLinkedDeletion() {
        context.disposableScope().register(
            context.services().get(MeshEditParticipation.class).participate(deletion ->
                deletion.mirrorAxis().enabled()
                    ? context.services().get(MeshMirrorCounterparts.class).mirrorOf(deletion)
                    : MeshEditContribution.none()
            )
        );
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
            logger.warn("MeshEditMirrorAxisEnhancePlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
