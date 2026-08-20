package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;


/** Runtime-owned generation state used by the verified mesh-mirror hook path. */
public final class RuntimeMeshMirrorAxisService implements MeshMirrorAxisService {
    private volatile float angleDegrees;
    private volatile AxisState axisState;
    private volatile long generation;
    private MeshMirrorGeometry.Line cachedLine;
    private long cachedGeneration = -1;

    @Override
    public float currentAngleDegrees() {
        return angleDegrees;
    }

    @Override
    public synchronized void setCurrentAngleDegrees(final float angleDegrees) {
        if (!Float.isFinite(angleDegrees)) {
            throw new IllegalArgumentException("angleDegrees must be finite");
        }
        float normalized = angleDegrees % 360.0f;
        if (normalized <= -180.0f) normalized += 360.0f;
        if (normalized > 180.0f) normalized -= 360.0f;
        final float value = normalized == -0.0f ? 0.0f : normalized;
        if (Float.compare(this.angleDegrees, value) != 0) {
            this.angleDegrees = value;
            generation++;
        }
    }

    synchronized void observeAxis(
        final float axisValue,
        final boolean vertical,
        final float pivotX,
        final float pivotY
    ) {
        if (!Float.isFinite(axisValue) || !Float.isFinite(pivotX) || !Float.isFinite(pivotY)) return;
        final AxisState next = new AxisState(axisValue, vertical, pivotX, pivotY);
        if (!next.equals(axisState)) {
            axisState = next;
            generation++;
        }
    }

    synchronized void observePivot(final float pivotX, final float pivotY) {
        if (!Float.isFinite(pivotX) || !Float.isFinite(pivotY)) return;
        final AxisState current = axisState;
        final AxisState next = current == null
            ? new AxisState(0.0f, true, pivotX, pivotY)
            : new AxisState(current.axisValue(), current.vertical(), pivotX, pivotY);
        if (!next.equals(current)) {
            axisState = next;
            generation++;
        }
    }

    synchronized void clearPivot() {
        if (axisState != null) {
            axisState = null;
            generation++;
        }
    }

    synchronized MeshMirrorGeometry.Line resolveLine() {
        if (axisState == null || angleDegrees == 0.0f) return null;
        if (generation != cachedGeneration) {
            cachedLine = MeshMirrorGeometry.rotatedAxis(
                axisState.axisValue(),
                axisState.pivotX(),
                axisState.pivotY(),
                axisState.vertical(),
                angleDegrees
            );
            cachedGeneration = generation;
        }
        return cachedLine;
    }

    synchronized long generation() {
        return generation;
    }

    /**
     * Returns the mirror axis to its neutral state: zero angle and no observed axis or pivot.
     *
     * <p>Bumps the generation counter, so any cached line is recomputed rather than reused. With a
     * zero angle the hook path falls back to the host's own unrotated behaviour until an angle is set
     * again. Called when the host swaps mesh-edit sessions.
     */
    public synchronized void resetSession() {
        angleDegrees = 0.0f;
        axisState = null;
        generation++;
    }


    private record AxisState(float axisValue, boolean vertical, float pivotX, float pivotY) { }
}
