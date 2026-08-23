package dev.turboism.sdk.cubism.mesh;


/**
 * The mirror axis as it stands during one edit.
 *
 * <p>{@code enabled} reflects the host's own mirror toggle, not a Turboism setting. A plugin
 * deciding whether to contribute should consult this rather than inventing its own condition.</p>
 */
public record MirrorAxisState(boolean enabled, float angleDegrees) {

    public MirrorAxisState {
        if (!Float.isFinite(angleDegrees)) {
            throw new IllegalArgumentException("mirror axis angle must be finite");
        }
    }
}
