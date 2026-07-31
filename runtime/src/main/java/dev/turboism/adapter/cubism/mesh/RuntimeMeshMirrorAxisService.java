package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;

import java.util.Locale;

/** Runtime-owned generation state used by the verified mesh-mirror hook path. */
public final class RuntimeMeshMirrorAxisService implements MeshMirrorAxisService {
    private volatile float angleDegrees;
    private volatile AxisState axisState;
    private volatile long generation;

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

    synchronized MeshMirrorGeometry.Line resolveLine(final Object mirrorState) {
        final AxisState state = stateFrom(mirrorState, axisState);
        if (state == null || angleDegrees == 0.0f) return null;
        return MeshMirrorGeometry.rotatedAxis(
            state.axisValue(),
            state.vertical() ? state.pivotY() : state.pivotX(),
            state.vertical(),
            angleDegrees
        );
    }

    synchronized long generation() {
        return generation;
    }

    public synchronized void resetSession() {
        angleDegrees = 0.0f;
        axisState = null;
        generation++;
    }

    private static AxisState stateFrom(final Object mirrorState, final AxisState fallback) {
        if (fallback == null) return null;
        if (mirrorState == null) return fallback;
        try {
            final Object mode = mirrorState.getClass().getMethod("b").invoke(mirrorState);
            final Object rawAxis = mirrorState.getClass().getMethod("c").invoke(mirrorState);
            if (!(rawAxis instanceof Number number)) return null;
            return new AxisState(
                number.floatValue(),
                orientation(mode, fallback.vertical()),
                fallback.pivotX(),
                fallback.pivotY()
            );
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean orientation(final Object mode, final boolean fallback) {
        if (mode == null) throw new IllegalArgumentException("mirror orientation is unavailable");
        final String value = String.valueOf(mode).toUpperCase(Locale.ROOT);
        if ("HORIZONTAL".equals(value) || "B".equals(value)) return false;
        if ("VERTICAL".equals(value) || "A".equals(value)) return true;
        throw new IllegalArgumentException("mirror orientation is unsupported");
    }

    private record AxisState(float axisValue, boolean vertical, float pivotX, float pivotY) { }
}
