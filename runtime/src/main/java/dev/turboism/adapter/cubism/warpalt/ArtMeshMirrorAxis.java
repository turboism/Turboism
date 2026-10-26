package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry;
import dev.turboism.adapter.cubism.mesh.MeshMirrorVertexResolver;
import java.util.Arrays;

/** Model-canvas coordinates; independent of camera pan, zoom and reflection. */
final class ArtMeshMirrorAxis {
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

    private Point pivot;
    private double angle;
    private float rotationRadius = 64;

    ArtMeshMirrorAxis(Point pivot, boolean horizontal) {
        this.pivot = pivot;
        preset(horizontal);
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

    Point direction() {
        return new Point((float) Math.cos(angle), (float) Math.sin(angle));
    }

    void preset(boolean horizontal) {
        angle = horizontal ? 0 : Math.PI / 2;
    }

    void translate(Point delta) {
        pivot = pivot.plus(delta);
    }

    void slidePivot(Point target) {
        Point d = direction();
        Point delta = target.minus(pivot);
        float t = delta.x * d.x + delta.y * d.y;
        pivot = pivot.plus(new Point(d.x * t, d.y * t));
    }

    void rotate(Point target) {
        Point delta = target.minus(pivot);
        if (delta.x == 0 && delta.y == 0) return;
        double next = Math.atan2(delta.y, delta.x);
        double snap = Math.rint(next / (Math.PI / 2)) * Math.PI / 2;
        angle = Math.abs(next - snap) < Math.toRadians(3) ? snap : next;
    }

    private MeshMirrorGeometry.Line line() {
        Point d = direction();
        return new MeshMirrorGeometry.Line(
                new MeshMirrorGeometry.Point(pivot.x, pivot.y), new MeshMirrorGeometry.Point(d.x, d.y));
    }

    Point reflectVector(Point vector) {
        MeshMirrorGeometry.Point d = line().direction();
        MeshMirrorGeometry.Point result = MeshMirrorGeometry.reflect(
                new MeshMirrorGeometry.Line(new MeshMirrorGeometry.Point(0, 0), d), vector.x, vector.y);
        return new Point(result.x(), result.y());
    }

    Point reflect(Point point) {
        MeshMirrorGeometry.Point result = MeshMirrorGeometry.reflect(line(), point.x, point.y);
        return new Point(result.x(), result.y());
    }

    int[] pair(Point[] points, float tolerance) {
        return MeshMirrorVertexResolver.pairs(
                Arrays.stream(points)
                        .map(p -> new MeshMirrorGeometry.Point(p.x, p.y))
                        .toList(),
                line(),
                tolerance);
    }
}
