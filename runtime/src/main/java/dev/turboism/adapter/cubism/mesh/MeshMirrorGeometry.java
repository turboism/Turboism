package dev.turboism.adapter.cubism.mesh;

/** Pure geometry ported from the legacy mesh mirror-axis implementation. */
public final class MeshMirrorGeometry {

    private MeshMirrorGeometry() { }

    /**
     * Rotates the mirror axis around the pivot, matching the legacy semantics:
     * the axis anchor (axisValue, pivotY) for a vertical axis, or (pivotX,
     * axisValue) for a horizontal axis, is rotated about the pivot by
     * angleDegrees; the direction is rotated by the same angle. Equivalent to
     * lineFromEndpoints(rotateAround(start, pivot, angle), rotateAround(end, pivot, angle)).
     */
    public static Line rotatedAxis(
        final float axisValue,
        final float pivotX,
        final float pivotY,
        final boolean vertical,
        final float angleDegrees
    ) {
        final double radians = Math.toRadians(angleDegrees);
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        final Point anchor = vertical
            ? new Point(pivotX + (axisValue - pivotX) * cos, pivotY + (axisValue - pivotX) * sin)
            : new Point(pivotX - (axisValue - pivotY) * sin, pivotY + (axisValue - pivotY) * cos);
        return new Line(anchor, vertical ? new Point(-sin, cos) : new Point(cos, sin));
    }

    public static Point reflect(final Line line, final float x, final float y) {
        final float distance = signedDistance(line, x, y);
        final float normalX = -line.direction().y();
        final float normalY = line.direction().x();
        return new Point(x - 2.0f * distance * normalX, y - 2.0f * distance * normalY);
    }

    public static Point project(final Line line, final float x, final float y) {
        final float distance = signedDistance(line, x, y);
        final float normalX = -line.direction().y();
        final float normalY = line.direction().x();
        return new Point(x - distance * normalX, y - distance * normalY);
    }

    public static float distance(final Line line, final float x, final float y) {
        return Math.abs(signedDistance(line, x, y));
    }

    public static boolean hit(
        final Line line,
        final float x,
        final float y,
        final float threshold
    ) {
        return distance(line, x, y) < threshold;
    }

    private static float signedDistance(final Line line, final float x, final float y) {
        final float deltaX = x - line.anchor().x();
        final float deltaY = y - line.anchor().y();
        return deltaX * -line.direction().y() + deltaY * line.direction().x();
    }

    public record Point(float x, float y) { }

    public record Line(Point anchor, Point direction) { }
}
