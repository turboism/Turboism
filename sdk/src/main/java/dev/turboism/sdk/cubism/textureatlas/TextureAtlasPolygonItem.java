package dev.turboism.sdk.cubism.textureatlas;

import java.util.Objects;

/**
 * One item submitted to a polygon packing planner.
 *
 * <p>{@code width}/{@code height} are the issued pixel size and {@code outline} the
 * item contour in material-local coordinates (same space as the width/height and
 * the model rectangle). {@code currentMatrix} is the item's issued affine as six
 * doubles in {@code java.awt.geom.AffineTransform} order
 * {@code [m00, m10, m01, m11, m02, m12]}; it may be {@code null} when the item has
 * never been placed. {@code currentlyPlaced} tells whether the item currently sits
 * inside the page (versus the overflow list).</p>
 */
public record TextureAtlasPolygonItem(
    String textureId,
    int width,
    int height,
    TextureAtlasOutline outline,
    TextureAtlasItemLayoutPolicy policy,
    TextureAtlasOutlineSource outlineSource,
    double[] currentMatrix,
    boolean currentlyPlaced
) {

    public TextureAtlasPolygonItem {
        Objects.requireNonNull(textureId, "textureId");
        if (textureId.isBlank()) {
            throw new IllegalArgumentException("textureId is required");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
        Objects.requireNonNull(outline, "outline");
        Objects.requireNonNull(outlineSource, "outlineSource");
        if (policy == null) {
            policy = TextureAtlasItemLayoutPolicy.participating(textureId);
        } else if (!textureId.equals(policy.textureId())) {
            throw new IllegalArgumentException("policy textureId does not match item");
        }
        if (currentMatrix != null) {
            if (currentMatrix.length != 6) {
                throw new IllegalArgumentException("currentMatrix must have six elements");
            }
            for (final double v : currentMatrix) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("currentMatrix must be finite");
                }
            }
            currentMatrix = currentMatrix.clone();
        }
    }

    @Override
    public double[] currentMatrix() {
        return currentMatrix == null ? null : currentMatrix.clone();
    }

    /** Uniform scale implied by the current matrix, or {@code 1} when unplaced. */
    public double currentScale() {
        if (currentMatrix == null) {
            return 1.0;
        }
        return Math.sqrt(Math.abs(
            currentMatrix[0] * currentMatrix[3] - currentMatrix[1] * currentMatrix[2]));
    }

    /** Rotation in degrees implied by the current matrix, or {@code 0} when unplaced. */
    public double currentAngleDeg() {
        if (currentMatrix == null) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(currentMatrix[1], currentMatrix[0]));
    }
}
