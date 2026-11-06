package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Declarative contributions for Cubism's native mesh-edit tool area.
 *
 * @deprecated use {@link MeshEditContributionSurface#contributeMirrorAxisAngleControl(
 *     MeshEditContributionSurface.MirrorAxisAngleControl)} instead: that surface consolidates the
 *     two ways a plugin takes part in a native mesh edit and is handed out by
 *     {@code PluginService.MESH_EDIT_CONTRIBUTION}. Scheduled for removal; every released
 *     operation keeps working through this thin adapter until then.
 */
@Deprecated(forRemoval = true)
@CubismEditor(from = "5.0.00")
public interface MeshEditUiService {

    /**
     * Contributes an angle control for the mesh mirror axis to the native mesh-edit tool area.
     *
     * @param contribution the control definition
     * @return the registration; closing it removes the contribution
     */
    Registration contributeMirrorAxisAngleControl(MirrorAxisAngleControl contribution);

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
    static MeshEditUiService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshEditUiService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl contribution) {
            Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException("meshEditUi service is not available");
        }
    }

    /**
     * Definition of one contributed mirror-axis angle control; degrees bound the range.
     *
     * @deprecated use {@link MeshEditContributionSurface.MirrorAxisAngleControl} instead, the
     *     control record of the consolidated contribution surface. Scheduled for removal with
     *     its owning interface.
     */
    @Deprecated(forRemoval = true)
    record MirrorAxisAngleControl(
            String contributionId,
            String label,
            String resetToolTip,
            float minimumDegrees,
            float maximumDegrees,
            float stepDegrees,
            Consumer<Float> onAngleChanged) {
        public MirrorAxisAngleControl {
            if (contributionId == null || contributionId.isBlank()) {
                throw new IllegalArgumentException("contributionId must not be blank");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            resetToolTip = resetToolTip == null ? "" : resetToolTip;
            if (!Float.isFinite(minimumDegrees)
                    || !Float.isFinite(maximumDegrees)
                    || !Float.isFinite(stepDegrees)
                    || minimumDegrees >= maximumDegrees
                    || stepDegrees <= 0.0f) {
                throw new IllegalArgumentException("mirror-axis angle range is invalid");
            }
            onAngleChanged = Objects.requireNonNull(onAngleChanged, "onAngleChanged");
        }
    }
}
