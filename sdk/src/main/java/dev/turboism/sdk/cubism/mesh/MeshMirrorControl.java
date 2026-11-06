package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import java.util.Set;

/**
 * Framework capability: mirror editing during a Cubism mesh edit, as one surface.
 *
 * <p>The four released single-purpose services this consolidates — the mirror-axis angle, the
 * counterpart rule, selected-point movement participation and the eligible-tool set — always
 * describe one feature: what the host mirrors, around which axis, and for which subtools. A
 * plugin that needs one of them needs the others, so they arrive together and answer to the
 * same model permissions.</p>
 *
 * <p>Mirroring only fires where the framework actually intercepts the host. On a host that
 * already performs the edit natively there is no interception and no callback, so behaviour
 * cannot be applied twice.</p>
 */
@Incubating
@CubismEditor(from = "5.0.00")
public interface MeshMirrorControl {

    /** Returns the current mirror-axis angle in degrees for this session. */
    float currentAngleDegrees();

    /**
     * Sets the mirror-axis angle for this session.
     *
     * @param angleDegrees the new angle in degrees; must be finite, normalized to (-180, 180]
     */
    void setCurrentAngleDegrees(float angleDegrees);

    /**
     * The counterparts of everything in {@code deletion}, using whichever resolver applies.
     *
     * <p>With the framework default this runs entirely inside the runtime against live host
     * geometry: nothing is copied across the boundary per point.</p>
     *
     * @param deletion the pending host deletion, backed by live host geometry
     * @return the counterpart contribution; {@link MeshEditContribution#none()} when the host
     *     mirror axis is disabled or nothing has a counterpart
     */
    MeshEditContribution mirrorOf(MeshDeletion deletion);

    /**
     * Replaces the default counterpart rule for this plugin. See
     * {@link MeshMirrorCounterpartResolver} for the cost this incurs.
     *
     * @param resolver the replacement rule
     * @return the registration; closing it restores the framework default
     */
    Registration overrideResolver(MeshMirrorCounterpartResolver resolver);

    /**
     * Activates this plugin's mirroring of host-initiated selected-point movement.
     *
     * @return the participation registration; closing it stops the mirroring
     */
    Registration participate();

    /**
     * Adds mesh subtools to the set that keeps mirror editing active.
     *
     * @param tools the additional tools mirror editing stays enabled for
     * @return the registration; closing it withdraws the extension
     */
    Registration extendEligibleTools(Set<MeshEditTool> tools);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static MeshMirrorControl unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshMirrorControl {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public float currentAngleDegrees() {
            throw unavailable();
        }

        @Override
        public void setCurrentAngleDegrees(final float angleDegrees) {
            throw unavailable();
        }

        @Override
        public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
            throw unavailable();
        }

        @Override
        public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
            throw unavailable();
        }

        @Override
        public Registration participate() {
            throw unavailable();
        }

        @Override
        public Registration extendEligibleTools(final Set<MeshEditTool> tools) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("meshMirrorControl service is not available");
        }
    }
}
