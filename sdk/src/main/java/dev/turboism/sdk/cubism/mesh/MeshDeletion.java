package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/**
 * What the host is about to delete, handed to participants before the deletion happens.
 *
 * <p>{@code mesh} is present only when the plugin has registered a custom counterpart resolver;
 * otherwise it is empty, because materialising it would cost a full copy the default path does
 * not need.</p>
 */
@PreviewApi
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
