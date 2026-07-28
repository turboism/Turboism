package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Immutable Rotation Deformer keyform committed as one Editor operation. */
@PreviewApi
public record RotationDeformerForm(
    float angle,
    float originX,
    float originY,
    float scale,
    boolean reflectedX,
    boolean reflectedY
) {

    public RotationDeformerForm {
        requireFinite(angle, "angle");
        requireFinite(originX, "originX");
        requireFinite(originY, "originY");
        requireFinite(scale, "scale");
        if (scale <= 0.0f) {
            throw new IllegalArgumentException("scale must be positive");
        }
    }

    public Point2 origin() {
        return new Point2(originX, originY);
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
