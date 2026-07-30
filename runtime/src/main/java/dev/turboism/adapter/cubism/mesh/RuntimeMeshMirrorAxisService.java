package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;

/** Runtime-owned session state used by the verified mesh-mirror hook path. */
public final class RuntimeMeshMirrorAxisService implements MeshMirrorAxisService {

    private volatile float angleDegrees;
    private volatile float pivotX;
    private volatile float pivotY;

    @Override
    public float currentAngleDegrees() {
        return angleDegrees;
    }

    @Override
    public void setCurrentAngleDegrees(final float angleDegrees) {
        if (!Float.isFinite(angleDegrees)) {
            throw new IllegalArgumentException("angleDegrees must be finite");
        }
        float normalized = angleDegrees % 360.0f;
        if (normalized <= -180.0f) normalized += 360.0f;
        if (normalized > 180.0f) normalized -= 360.0f;
        this.angleDegrees = normalized;
    }

    float pivotX() {
        return pivotX;
    }

    float pivotY() {
        return pivotY;
    }

    void setPivot(final float x, final float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        pivotX = x;
        pivotY = y;
    }
}
