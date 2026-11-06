package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshMirrorControl;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.Set;

/**
 * The consolidated mirror capability a plugin receives: this plugin's resolver slot composed
 * with the four session-shared mirror registries.
 *
 * <p>The registries stay shared because the hook path reads them once per host dispatch
 * ({@link NativeMeshMirrorBridge}); only the counterpart resolver is per plugin, so one plugin's
 * rule can never answer another plugin's deletion. This class owns no state of its own — it is
 * the one object a plugin needs instead of four, and the released single-purpose services keep
 * working unchanged beside it.</p>
 */
public final class RuntimeMeshMirrorControl implements MeshMirrorControl {

    private final RuntimeMeshMirrorAxisService axis;
    private final PluginMeshMirrorCounterparts counterparts;
    private final RuntimeMeshMirrorMoveParticipation moves;
    private final RuntimeMeshMirrorToolEligibility tools;

    /**
     * @param axis this session's mirror-axis state, never {@code null}
     * @param counterparts this plugin's counterpart view over the session-shared resolution,
     *     never {@code null}
     * @param moves the session-shared movement participation counter, never {@code null}
     * @param tools the session-shared eligible-tool registry, never {@code null}
     */
    public RuntimeMeshMirrorControl(
            final RuntimeMeshMirrorAxisService axis,
            final PluginMeshMirrorCounterparts counterparts,
            final RuntimeMeshMirrorMoveParticipation moves,
            final RuntimeMeshMirrorToolEligibility tools) {
        this.axis = Objects.requireNonNull(axis, "axis");
        this.counterparts = Objects.requireNonNull(counterparts, "counterparts");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    /**
     * Composes this session's shared mirror registries into one per-plugin control.
     *
     * @param axis the host session's mirror-axis state, never {@code null}
     * @return the control for one plugin; never the same instance twice
     */
    public static RuntimeMeshMirrorControl forSession(final RuntimeMeshMirrorAxisService axis) {
        return new RuntimeMeshMirrorControl(
                axis,
                new PluginMeshMirrorCounterparts(NativeMeshMirrorBridge.counterparts()),
                NativeMeshMirrorBridge.moveParticipation(),
                NativeMeshMirrorBridge.toolEligibility());
    }

    @Override
    public float currentAngleDegrees() {
        return axis.currentAngleDegrees();
    }

    @Override
    public void setCurrentAngleDegrees(final float angleDegrees) {
        axis.setCurrentAngleDegrees(angleDegrees);
    }

    @Override
    public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
        return counterparts.mirrorOf(deletion);
    }

    @Override
    public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
        return counterparts.overrideResolver(resolver);
    }

    @Override
    public Registration participate() {
        return moves.participate();
    }

    @Override
    public Registration extendEligibleTools(final Set<MeshEditTool> eligible) {
        return tools.extendEligibleTools(eligible);
    }
}
