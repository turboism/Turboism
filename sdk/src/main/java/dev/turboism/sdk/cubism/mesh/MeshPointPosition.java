package dev.turboism.sdk.cubism.mesh;


/** A finite position for a point Cubism has not assigned an identity yet. */
public record MeshPointPosition(float x, float y) {

    public MeshPointPosition {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("mesh point position must be finite");
        }
    }
}
