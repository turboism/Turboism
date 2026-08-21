package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/** A finite position for a point Cubism has not assigned an identity yet. */
@PreviewApi
public record MeshPointPosition(float x, float y) {

    public MeshPointPosition {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("mesh point position must be finite");
        }
    }
}
