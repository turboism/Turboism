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
}
