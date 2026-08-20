package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/**
 * Extra deletions a plugin wants folded into a host edit.
 *
 * <p>This is a description, not executable write logic. The runtime validates it against the live
 * mesh and applies it itself, so plugin code cannot directly mutate host geometry. The participant
 * that produces this value still runs synchronously; see {@link MeshEditParticipant}.</p>
 */
@PreviewApi
public record MeshEditContribution(List<MeshPointRef> points, List<MeshEdgeRef> edges) {

    private static final MeshEditContribution NONE = new MeshEditContribution(List.of(), List.of());

    public MeshEditContribution {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** Contribute nothing; the host edit proceeds exactly as it would unmodified. */
    public static MeshEditContribution none() {
        return NONE;
    }

    public static MeshEditContribution ofPoints(final List<MeshPointRef> points) {
        return new MeshEditContribution(points, List.of());
    }

    public static MeshEditContribution ofEdges(final List<MeshEdgeRef> edges) {
        return new MeshEditContribution(List.of(), edges);
    }

    public boolean isEmpty() {
        return points.isEmpty() && edges.isEmpty();
    }
}
