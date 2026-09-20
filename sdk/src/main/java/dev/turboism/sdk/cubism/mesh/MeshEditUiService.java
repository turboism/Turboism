package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/** Declarative contributions for Cubism's native mesh-edit tool area. */
public interface MeshEditUiService {

    Registration contributeMirrorAxisAngleControl(MirrorAxisAngleControl contribution);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshEditUiService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements MeshEditUiService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration contributeMirrorAxisAngleControl(
            final MirrorAxisAngleControl contribution
        ) {
            Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException(
                "meshEditUi service is not available");
        }
    }

    record MirrorAxisAngleControl(
        String contributionId,
        String label,
        String resetToolTip,
        float minimumDegrees,
        float maximumDegrees,
        float stepDegrees,
        Consumer<Float> onAngleChanged
    ) {
        public MirrorAxisAngleControl {
            if (contributionId == null || contributionId.isBlank()) {
                throw new IllegalArgumentException("contributionId must not be blank");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            resetToolTip = resetToolTip == null ? "" : resetToolTip;
            if (!Float.isFinite(minimumDegrees) || !Float.isFinite(maximumDegrees)
                || !Float.isFinite(stepDegrees) || minimumDegrees >= maximumDegrees
                || stepDegrees <= 0.0f) {
                throw new IllegalArgumentException("mirror-axis angle range is invalid");
            }
            onAngleChanged = Objects.requireNonNull(onAngleChanged, "onAngleChanged");
        }
    }
}
