package dev.turboism.sdk.cubism.mesh;


/** Session-scoped angle of Cubism's mesh-edit mirror axis. */
public interface MeshMirrorAxisService {

    float currentAngleDegrees();

    void setCurrentAngleDegrees(float angleDegrees);
}
