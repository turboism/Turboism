package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable complete ArtMesh geometry committed as one Editor operation. */
@PreviewApi
public record ArtMeshGeometry(
    List<Point2> positions,
    List<Point2> uvs,
    List<Integer> triangleIndices
) {

    public ArtMeshGeometry {
        positions = copyPoints(positions, "positions");
        uvs = copyPoints(uvs, "uvs");
        triangleIndices = List.copyOf(Objects.requireNonNull(
            triangleIndices,
            "triangleIndices"
        ));
        if (positions.size() != uvs.size()) {
            throw new IllegalArgumentException("positions and uvs must have the same size");
        }
        if (triangleIndices.size() % 3 != 0) {
            throw new IllegalArgumentException("triangleIndices must contain complete triangles");
        }
        for (Integer boxedIndex : triangleIndices) {
            final int index = Objects.requireNonNull(boxedIndex, "triangle index");
            if (index < 0 || index >= positions.size()) {
                throw new IllegalArgumentException("triangle index is outside the vertex range");
            }
        }
    }

    /**
     * Returns a copy of this geometry with vertex {@code index} moved to {@code (x, y)}
     * (Inspector {@code PointInfo} single-vertex move projection).
     *
     * <p>The Inspector PointInfo widget moves selected vertices of the current keyform — absolute
     * (set X/Y) or relative (delta) — one coordinate or one vertex at a time. This immutable
     * builder is the matching per-vertex primitive; combine several calls before one
     * {@code Drawable#replaceGeometry(ArtMeshGeometry)} to project a multi-selection move.</p>
     *
     * @throws IndexOutOfBoundsException when {@code index} is outside the vertex range
     */
    public ArtMeshGeometry withVertexPosition(final int index, final float x, final float y) {
        final ArrayList<Point2> changed = new ArrayList<>(positions);
        changed.set(index, new Point2(x, y));
        return new ArtMeshGeometry(changed, uvs, triangleIndices);
    }

    public ArtMeshGeometry withVertexUv(final int index, final float u, final float v) {
        final ArrayList<Point2> changed = new ArrayList<>(uvs);
        changed.set(index, new Point2(u, v));
        return new ArtMeshGeometry(positions, changed, triangleIndices);
    }

    private static List<Point2> copyPoints(final List<Point2> values, final String name) {
        final List<Point2> copy = List.copyOf(Objects.requireNonNull(values, name));
        copy.forEach(value -> Objects.requireNonNull(value, name + " element"));
        return copy;
    }
}
