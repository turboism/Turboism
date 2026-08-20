package dev.turboism.plugin.mesh;

import dev.turboism.plugin.mesh.service.MeshInspectorService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
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
            registerMirrorLinkedDeletion();
            context.disposableScope().register(
                context.meshEditUi().contributeMirrorAxisAngleControl(
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

    /** Called by the native-position mesh-edit control. */
    public void setMirrorAxisAngleDegrees(final float angleDegrees) {
        context.meshMirrorAxis().setCurrentAngleDegrees(angleDegrees);
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
            context.meshEditParticipation().participate(deletion ->
                deletion.mirrorAxis().enabled()
                    ? context.meshMirrorCounterparts().mirrorOf(deletion)
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
            logger.warn("MeshPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
