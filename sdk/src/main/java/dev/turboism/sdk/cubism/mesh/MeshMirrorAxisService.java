package dev.turboism.sdk.cubism.mesh;


/** Session-scoped angle of Cubism's mesh-edit mirror axis. */
public interface MeshMirrorAxisService {

    float currentAngleDegrees();

    void setCurrentAngleDegrees(float angleDegrees);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshMirrorAxisService unavailable() {
        return Unavailable.INSTANCE;
    }

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
