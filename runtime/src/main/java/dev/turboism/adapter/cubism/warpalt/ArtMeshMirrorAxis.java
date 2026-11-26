package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry;
import dev.turboism.adapter.cubism.mesh.MeshMirrorVertexResolver;

/** Model-canvas coordinates; independent of camera pan, zoom and reflection. */
final class ArtMeshMirrorAxis {
    private static final float DEFAULT_ROTATION_RADIUS = 64.0f;
    private static final double QUARTER_TURN = Math.PI / 2.0;
    private static final double SNAP_THRESHOLD_RADIANS = Math.toRadians(3.0);
    private static final float INITIAL_POSITION_TOLERANCE = 1.0e-3f;
    private static final double INITIAL_ANGLE_TOLERANCE_RADIANS = Math.toRadians(0.05);

    record Point(float x, float y) {
        Point {
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("non-finite point");
        }

        Point plus(Point p) {
            return new Point(x + p.x, y + p.y);
        }

        Point minus(Point p) {
            return new Point(x - p.x, y - p.y);
        }
    }

    private final Point initialPivot;
    private final double initialAngle;
    private Point pivot;
    private double angle;
    private float dirX;
    private float dirY;
    private float rotationRadius = DEFAULT_ROTATION_RADIUS;

    ArtMeshMirrorAxis(Point pivot, boolean horizontal) {
        this.initialPivot = pivot;
        this.pivot = pivot;
        preset(horizontal);
        this.initialAngle = angle;
    }

    boolean isAtInitialState() {
        return Math.hypot(pivot.x - initialPivot.x, pivot.y - initialPivot.y) <= INITIAL_POSITION_TOLERANCE
                && Math.abs(angle - initialAngle) <= INITIAL_ANGLE_TOLERANCE_RADIANS;
    }

    Point pivot() {
        return pivot;
    }

    double angle() {
        return angle;
    }

    float rotationRadius() {
        return rotationRadius;
    }

    void setRotationRadius(float radius) {
        if (!Float.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("invalid rotation radius");
        rotationRadius = radius;
    }

    float dirX() {
        return dirX;
    }

    float dirY() {
        return dirY;
    }

    Point direction() {
        return new Point(dirX, dirY);
    }

    private void setAngle(double next) {
        angle = next;
        dirX = (float) Math.cos(next);
        dirY = (float) Math.sin(next);
    }

    void preset(boolean horizontal) {
        setAngle(horizontal ? 0.0 : QUARTER_TURN);
    }

    void restore(Point savedPivot, double savedAngle) {
        if (savedPivot == null || !Double.isFinite(savedAngle)) {
            throw new IllegalArgumentException("invalid saved axis");
        }
        pivot = savedPivot;
        setAngle(savedAngle);
    }

    void translate(Point delta) {
        pivot = pivot.plus(delta);
    }

    void slidePivot(Point target) {
        Point delta = target.minus(pivot);
        float t = delta.x * dirX + delta.y * dirY;
        pivot = pivot.plus(new Point(dirX * t, dirY * t));
    }

    void rotate(Point target) {
        Point delta = target.minus(pivot);
        if (delta.x == 0 && delta.y == 0) return;
        double next = Math.atan2(delta.y, delta.x);
        double snap = Math.rint(next / QUARTER_TURN) * QUARTER_TURN;
        setAngle(Math.abs(next - snap) < SNAP_THRESHOLD_RADIANS ? snap : next);
    }

    private MeshMirrorGeometry.Line line() {
        return new MeshMirrorGeometry.Line(
                new MeshMirrorGeometry.Point(pivot.x, pivot.y), new MeshMirrorGeometry.Point(dirX, dirY));
    }

    Point reflectVector(Point vector) {
        MeshMirrorGeometry.Point result = MeshMirrorGeometry.reflect(
                new MeshMirrorGeometry.Line(
                        new MeshMirrorGeometry.Point(0, 0), new MeshMirrorGeometry.Point(dirX, dirY)),
                vector.x,
                vector.y);
        return new Point(result.x(), result.y());
    }

    Point reflect(Point point) {
        MeshMirrorGeometry.Point result = MeshMirrorGeometry.reflect(line(), point.x, point.y);
        return new Point(result.x(), result.y());
    }

    int[] pair(Point[] points, float tolerance) {
        float[] xy = new float[points.length * 2];
        for (int i = 0; i < points.length; i++) {
            xy[i * 2] = points[i].x;
            xy[i * 2 + 1] = points[i].y;
        }
        return pair(xy, points.length, tolerance);
    }

    int[] pair(float[] xy, int count, float tolerance) {
        return MeshMirrorVertexResolver.pairs(xy, count, pivot.x, pivot.y, dirX, dirY, tolerance);
    }
}
