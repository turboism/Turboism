package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/** Edge classification carried across the boundary in place of the host's own enum. */
@PreviewApi
public enum MeshEdgeKind {
    /** An edge on the outer boundary of the mesh. */
    BORDER,
    /** An edge inside the mesh. */
    INNER,
    /** The host reported a classification this SDK version does not model. */
    UNKNOWN
}
