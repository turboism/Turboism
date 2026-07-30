package dev.turboism.adapter.cubism.mesh;

/** Pure geometry ported from the legacy mesh mirror-axis implementation. */
public final class MeshMirrorGeometry {

    private MeshMirrorGeometry() { }

    public static Line rotatedAxis(
        final float axisValue,
        final float pivotCoordinate,
        final boolean vertical,
        final float angleDegrees
    ) {
        final Point anchor = vertical
            ? new Point(axisValue, pivotCoordinate)
            : new Point(pivotCoordinate, axisValue);
        final double radians = Math.toRadians(angleDegrees);
        final float x = (float) Math.cos(radians);
        final float y = (float) Math.sin(radians);
        return new Line(anchor, vertical ? new Point(-y, x) : new Point(x, y));
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
