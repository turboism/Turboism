package dev.turboism.sdk.cubism.textureatlas;

import java.util.Objects;

/**
 * One placed item in a polygon layout plan.
 *
 * <p>{@code x}/{@code y} is the translation applied to the item's material-local
 * outline, {@code angleDeg} the counter-clockwise rotation in degrees following
 * {@code java.awt.geom.AffineTransform.rotate} semantics, and {@code scale} the
 * uniform scale. The effective transform is {@code T(x,y) . R(angle) . S(scale)}
 * applied to the item outline and model rectangle.</p>
 */
public record TextureAtlasPolygonPlacement(
    String textureId,
    double x,
    double y,
    double angleDeg,
    double scale
) {

    public TextureAtlasPolygonPlacement {
        Objects.requireNonNull(textureId, "textureId");
        if (textureId.isBlank()) {
            throw new IllegalArgumentException("textureId is required");
        }
        for (final double v : new double[] {x, y, angleDeg, scale}) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("placement values must be finite");
            }
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be positive");
        }
    }
}
