package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/**
 * A mesh edge as seen by a plugin, identified by its two endpoint ids.
 *
 * <p>Endpoints are stored in ascending id order so that two references to the same edge
 * compare equal regardless of the direction they were discovered in.</p>
 */
@PreviewApi
public record MeshEdgeRef(int startPointId, int endPointId, MeshEdgeKind kind) {

    public MeshEdgeRef {
        if (startPointId < 0 || endPointId < 0) {
            throw new IllegalArgumentException("mesh edge endpoint ids must not be negative");
        }
        if (startPointId == endPointId) {
            throw new IllegalArgumentException("a mesh edge must join two distinct points");
        }
        if (kind == null) kind = MeshEdgeKind.UNKNOWN;
        if (startPointId > endPointId) {
            final int swap = startPointId;
            startPointId = endPointId;
            endPointId = swap;
        }
    }
}
