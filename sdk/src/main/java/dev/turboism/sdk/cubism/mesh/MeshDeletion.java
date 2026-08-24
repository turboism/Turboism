package dev.turboism.sdk.cubism.mesh;


import java.util.List;
import java.util.Objects;

/**
 * What the host is about to delete, handed to participants before the deletion happens.
 *
 * <p>{@code mesh} is empty on the participation callback's synchronous fast path. A custom
 * counterpart resolver receives the materialised live snapshot through its own {@code mesh}
 * argument; keeping it out of this shared event avoids copying geometry for participants that
 * never request override resolution.</p>
 */
public record MeshDeletion(
    List<MeshPointRef> points,
    List<MeshEdgeRef> edges,
    MirrorAxisState mirrorAxis,
    MeshSnapshot mesh
) {

    public MeshDeletion {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        Objects.requireNonNull(mirrorAxis, "mirrorAxis");
        mesh = mesh == null ? MeshSnapshot.empty() : mesh;
    }
}
