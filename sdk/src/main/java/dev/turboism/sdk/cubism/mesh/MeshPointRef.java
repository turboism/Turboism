package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/**
 * A mesh point as seen by a plugin: a host-assigned identity plus its position.
 *
 * <p>The identity is what the host uses to match points across an edit; the position is a
 * snapshot and may be stale by the time a contribution is applied, which is why the runtime
 * revalidates every contribution against the live mesh.</p>
 */
@PreviewApi
public record MeshPointRef(int id, float x, float y) {

    public MeshPointRef {
        if (id < 0) throw new IllegalArgumentException("mesh point id must not be negative");
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("mesh point position must be finite");
        }
    }
}
