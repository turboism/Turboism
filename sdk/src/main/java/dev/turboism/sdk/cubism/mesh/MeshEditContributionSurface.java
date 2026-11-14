package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Framework capability: what a plugin contributes into a mesh edit, as one surface.
 *
 * <p>Both released halves answer the same question — how does this plugin take part in the
 * native mesh editor — one from the edit side (a participant that folds deletions into the
 * host's own Undo group) and one from the tool area (a mirror-axis angle control mounted into
 * the native row). A plugin that does either has already declared the permissions both need,
 * so the contribution surface is handed out once.</p>
 *
 * <p>Contributions are descriptions, not executable write logic: the runtime validates them
 * against the live mesh and applies them itself, so plugin code cannot directly mutate host
 * geometry.</p>
 */
@CubismEditor(from = "5.0.00")
public interface MeshEditContributionSurface {

    /**
     * Takes part in host-initiated mesh edits.
     *
     * @param participant the callback consulted while the host is deleting geometry
     * @return the participation registration; closing it withdraws the callback
     * @throws SecurityException if the plugin does not hold the mesh write permission
     */
    Registration participate(MeshEditParticipant participant);

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
    static MeshEditContributionSurface unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshEditContributionSurface {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration participate(final MeshEditParticipant participant) {
            throw unavailable();
        }

        @Override
        public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl contribution) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("meshEditContribution service is not available");
        }
    }

    /** Definition of one contributed mirror-axis angle control; degrees bound the range. */
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
