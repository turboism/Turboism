package dev.turboism.sdk.cubism.mesh;


/** Session-scoped angle of Cubism's mesh-edit mirror axis. */
public interface MeshMirrorAxisService {

    /** Returns the current mirror-axis angle in degrees for this session. */
    float currentAngleDegrees();

    /**
     * Sets the mirror-axis angle for this session.
     *
     * @param angleDegrees the new angle in degrees
     */
    void setCurrentAngleDegrees(float angleDegrees);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static MeshMirrorAxisService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshMirrorAxisService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public float currentAngleDegrees() {
            throw unavailable();
        }

        @Override public void setCurrentAngleDegrees(final float angleDegrees) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("meshMirrorAxis service is not available");
        }
    }
}
