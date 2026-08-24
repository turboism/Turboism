package dev.turboism.sdk.cubism.mesh;


import java.util.List;
import java.util.Objects;

/**
 * An immutable view of one mesh.
 *
 * <p>Materialising a snapshot copies every point and edge across the boundary, so the runtime
 * builds one only for the overridable counterpart path. The framework's default resolution
 * never needs it.</p>
 */
public record MeshSnapshot(List<MeshPointRef> points, List<MeshEdgeRef> edges) {

    public MeshSnapshot {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** Returns a snapshot containing no points or edges. */
    public static MeshSnapshot empty() {
        return new MeshSnapshot(List.of(), List.of());
    }
}
