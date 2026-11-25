package dev.turboism.adapter.cubism.mesh;

import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry.Line;
import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry.Point;

/** Runtime-owned generation state used by the verified mesh-mirror hook path. */
public final class RuntimeMeshMirrorAxisService {

    private static final float FULL_TURN = 360.0f;
    private static final float HALF_TURN = FULL_TURN / 2.0f;

    private volatile float angleDegrees;
    private volatile AxisState axisState;
    private volatile long generation;
    private MeshMirrorGeometry.Line cachedLine;
    private long cachedGeneration = -1;
    private float handleOffset;

    /** Returns the axis angle in degrees, normalized to one half-open turn. */
    public float currentAngleDegrees() {
        return angleDegrees;
    }

    /**
     * Stores a finite axis angle normalized to one half-open turn and advances the generation.
     *
     * @param angleDegrees the requested angle in degrees, never {@code NaN} or infinite
     */
    public synchronized void setCurrentAngleDegrees(final float angleDegrees) {
        if (!Float.isFinite(angleDegrees)) {
            throw new IllegalArgumentException("angleDegrees must be finite");
        }
        float normalized = angleDegrees % FULL_TURN;
        if (normalized <= -HALF_TURN) normalized += FULL_TURN;
        if (normalized > HALF_TURN) normalized -= FULL_TURN;
        final float value = normalized == -0.0f ? 0.0f : normalized;
        if (Float.compare(this.angleDegrees, value) != 0) {
            this.angleDegrees = value;
            generation++;
        }
    }

    synchronized void observeAxis(
            final float axisValue, final boolean vertical, final float pivotX, final float pivotY) {
        if (!Float.isFinite(axisValue) || !Float.isFinite(pivotX) || !Float.isFinite(pivotY)) return;
        final AxisState next = new AxisState(axisValue, vertical, pivotX, pivotY);
        if (!next.equals(axisState)) {
            if (axisState != null && axisState.vertical() != vertical) handleOffset = 0.0f;
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
        handleOffset = 0.0f;
        if (axisState != null) {
            axisState = null;
            generation++;
        }
    }

    synchronized MeshMirrorGeometry.Line resolveLine() {
        return angleDegrees == 0.0f ? null : guideLine();
    }

    synchronized Line guideLine() {
        if (axisState == null) return null;
        if (generation != cachedGeneration) {
            cachedLine = MeshMirrorGeometry.rotatedAxis(
                    axisState.axisValue(), axisState.pivotX(), axisState.pivotY(), axisState.vertical(), angleDegrees);
            cachedGeneration = generation;
        }
        return cachedLine;
    }

    synchronized Point handleCenter(final Line line) {
        final Point anchor = line.anchor();
        final Point direction = line.direction();
        return new Point(anchor.x() + direction.x() * handleOffset, anchor.y() + direction.y() * handleOffset);
    }

    /** Expresses a canvas handle position in the host's scalar position control. */
    synchronized Placement placement(final Point center, final float angle) {
        final AxisState state = axisState;
        if (state == null) return null;
        final float originValue = state.vertical() ? state.pivotX() : state.pivotY();
        final Line origin =
                MeshMirrorGeometry.rotatedAxis(originValue, state.pivotX(), state.pivotY(), state.vertical(), angle);
        final Point direction = origin.direction();
        final float dx = center.x() - origin.anchor().x();
        final float dy = center.y() - origin.anchor().y();
        final float normalOffset =
                state.vertical() ? dx * direction.y() - dy * direction.x() : dy * direction.x() - dx * direction.y();
        return new Placement(originValue + normalOffset, dx * direction.x() + dy * direction.y());
    }

    synchronized void applyPlacement(final Placement placement, final float actualPosition) {
        if (axisState == null) return;
        handleOffset = placement.handleOffset();
        observeAxis(actualPosition, axisState.vertical(), axisState.pivotX(), axisState.pivotY());
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
        handleOffset = 0.0f;
        generation++;
    }

    private record AxisState(float axisValue, boolean vertical, float pivotX, float pivotY) {}

    record Placement(float position, float handleOffset) {}
}
