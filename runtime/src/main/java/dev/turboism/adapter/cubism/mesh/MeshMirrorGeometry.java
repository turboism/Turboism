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

    /**
     * Mirrors a point across the axis line.
     *
     * @param line the mirror axis; its direction is assumed to be a unit vector, as produced by
     *             {@link #rotatedAxis}
     * @param x    the point's x coordinate in the same space as the line
     * @param y    the point's y coordinate in the same space as the line
     * @return the reflected point; a point on the line is returned unchanged
     */
    public static Point reflect(final Line line, final float x, final float y) {
        final float distance = signedDistance(line, x, y);
        final float normalX = -line.direction().y();
        final float normalY = line.direction().x();
        return new Point(x - 2.0f * distance * normalX, y - 2.0f * distance * normalY);
    }

    /**
     * Drops a point onto the axis line along the line's normal.
     *
     * @param line the mirror axis; its direction is assumed to be a unit vector
     * @param x    the point's x coordinate
     * @param y    the point's y coordinate
     * @return the closest point on the line, which lies on the line by construction
     */
    public static Point project(final Line line, final float x, final float y) {
        final float distance = signedDistance(line, x, y);
        final float normalX = -line.direction().y();
        final float normalY = line.direction().x();
        return new Point(x - distance * normalX, y - distance * normalY);
    }

    /**
     * Perpendicular distance from a point to the axis line.
     *
     * @param line the mirror axis; its direction is assumed to be a unit vector
     * @param x    the point's x coordinate
     * @param y    the point's y coordinate
     * @return the unsigned distance; which side of the line the point lies on is not reported
     */
    public static float distance(final Line line, final float x, final float y) {
        return Math.abs(signedDistance(line, x, y));
    }

    /**
     * Whether a point is close enough to the axis to count as grabbing it.
     *
     * @param line      the mirror axis; its direction is assumed to be a unit vector
     * @param x         the point's x coordinate
     * @param y         the point's y coordinate
     * @param threshold the pick radius, in the same units as the coordinates
     * @return {@code true} when the perpendicular distance is strictly less than {@code threshold};
     *         a zero or negative threshold therefore never hits
     */
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

    /**
     * A 2D coordinate pair, also used to carry a direction vector.
     *
     * @param x the horizontal component
     * @param y the vertical component
     */
    public record Point(float x, float y) { }

    /**
     * An infinite line given by a point on it and a direction.
     *
     * <p>The geometry helpers assume {@code direction} has unit length; lines built by
     * {@link MeshMirrorGeometry#rotatedAxis} satisfy this. Neither component is validated.
     *
     * @param anchor    a point lying on the line
     * @param direction the line's direction, expected to be normalised
     */
    public record Line(Point anchor, Point direction) { }
}
