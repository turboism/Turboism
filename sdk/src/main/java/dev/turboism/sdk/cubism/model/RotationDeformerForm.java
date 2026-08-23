package dev.turboism.sdk.cubism.model;


/** Immutable Rotation Deformer keyform committed as one Editor operation. */
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

    /**
     * @return the pivot the deformer rotates and scales about, as a point;
     *     both coordinates are finite
     */
    public Point2 origin() {
        return new Point2(originX, originY);
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
