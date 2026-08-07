package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/** Session-scoped angle of Cubism's mesh-edit mirror axis. */
@PreviewApi
public interface MeshMirrorAxisService {

    float currentAngleDegrees();

    void setCurrentAngleDegrees(float angleDegrees);
}
