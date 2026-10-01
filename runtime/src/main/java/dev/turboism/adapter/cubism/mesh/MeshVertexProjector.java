package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates and projects evaluated flat XY pairs into the active component coordinate space. */
final class MeshVertexProjector {
    private MeshVertexProjector() {}

    static List<Point2> project(final float[] evaluated, final int vertexCount, final PointProjector projector) {
        Objects.requireNonNull(evaluated, "evaluated");
        Objects.requireNonNull(projector, "projector");
        if (vertexCount < 0) throw new IllegalArgumentException("vertexCount must be non-negative");
        final int size = evaluated.length;
        if ((size & 1) != 0) {
            throw new IllegalStateException("evaluated vertex positions do not contain XY pairs: " + size);
        }
        if (size / 2 != vertexCount) {
            throw new IllegalStateException(
                    "evaluated vertex count does not match the editable mesh: " + (size / 2) + " != " + vertexCount);
        }
        for (int index = 0; index < size; index++) {
            if (!Float.isFinite(evaluated[index])) {
                throw new IllegalStateException("evaluated vertex position is not finite at flat index " + index);
            }
        }
        final ArrayList<Point2> projected = new ArrayList<>(vertexCount);
        for (int index = 0; index < vertexCount; index++) {
            final Point2 point;
            try {
                point = Objects.requireNonNull(
                        projector.project(evaluated[index * 2], evaluated[index * 2 + 1]), "projected point");
            } catch (RuntimeException failure) {
                throw new IllegalStateException("camera projection failed at vertex index " + index, failure);
            }
            if (!Float.isFinite(point.x()) || !Float.isFinite(point.y())) {
                throw new IllegalStateException("camera projection is not finite at vertex index " + index);
            }
            projected.add(point);
        }
        return List.copyOf(projected);
    }

    @FunctionalInterface
    interface PointProjector {
        Point2 project(float x, float y);
    }
}
