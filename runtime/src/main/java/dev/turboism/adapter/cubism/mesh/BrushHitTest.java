package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure component-space circle and round-cap capsule hit testing for selection brushes. */
final class BrushHitTest {
    private BrushHitTest() {}

    static List<Integer> indicesInCircle(
            final List<Point2> points, final float centerX, final float centerY, final float radius) {
        Objects.requireNonNull(points, "points");
        requireRadius(radius);
        final ArrayList<Integer> indices = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            final Point2 point = Objects.requireNonNull(points.get(index), "points[" + index + "]");
            if (contains(point.x(), point.y(), centerX, centerY, radius)) indices.add(index);
        }
        return List.copyOf(indices);
    }

    static boolean contains(
            final float x, final float y, final float centerX, final float centerY, final float radius) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(centerX, "centerX");
        requireFinite(centerY, "centerY");
        requireRadius(radius);
        final float dx = x - centerX;
        final float dy = y - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    static List<Integer> indicesWithinStroke(
            final List<Point2> points,
            final float startX,
            final float startY,
            final float endX,
            final float endY,
            final float radius) {
        Objects.requireNonNull(points, "points");
        requireRadius(radius);
        final ArrayList<Integer> indices = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            final Point2 point = Objects.requireNonNull(points.get(index), "points[" + index + "]");
            if (withinStroke(point.x(), point.y(), startX, startY, endX, endY, radius)) indices.add(index);
        }
        return List.copyOf(indices);
    }

    static boolean withinStroke(
            final float x,
            final float y,
            final float startX,
            final float startY,
            final float endX,
            final float endY,
            final float radius) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(startX, "startX");
        requireFinite(startY, "startY");
        requireFinite(endX, "endX");
        requireFinite(endY, "endY");
        requireRadius(radius);
        final float dx = endX - startX;
        final float dy = endY - startY;
        final float lengthSquared = dx * dx + dy * dy;
        final float projection = lengthSquared == 0.0f ? 0.0f : ((x - startX) * dx + (y - startY) * dy) / lengthSquared;
        final float clamped = Math.max(0.0f, Math.min(1.0f, projection));
        final float nearX = startX + clamped * dx;
        final float nearY = startY + clamped * dy;
        final float px = x - nearX;
        final float py = y - nearY;
        return px * px + py * py <= radius * radius;
    }

    private static void requireRadius(final float radius) {
        requireFinite(radius, "radius");
        if (radius < 0.0f) throw new IllegalArgumentException("radius must be non-negative");
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
