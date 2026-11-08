package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;

/**
 * Maps raster pixel coordinates back into the uv space an ArtMesh samples.
 *
 * <p>A texture solution is computed from {@link TexturePixels#argb()} pixels, whose coordinates
 * live in the host's cached-image raster. Applying that result needs the opposite direction of
 * {@link TexturePixels#uvToPixel()}, so this type inverts the published affine instead of
 * re-deriving a scale from the raster extent.</p>
 *
 * <p>The host may publish a non-invertible affine — a zero-width canvas or a collapsed squeeze —
 * and pixel coordinates still have to reach uv space. Such a request falls back to the
 * normalized raster extent, which is the documented shape of the default affine.</p>
 */
public final class TextureUvMapping {

    /** Element count of the published row-major 2x3 affine. */
    private static final int UV_TO_PIXEL_LENGTH = 6;

    /** Determinant magnitude below which the published affine carries no invertible scale. */
    private static final double SINGULAR_DETERMINANT = 1.0e-12;

    private final double a;
    private final double b;
    private final double c;
    private final double d;
    private final double tx;
    private final double ty;

    private TextureUvMapping(
            final double a, final double b, final double c, final double d, final double tx, final double ty) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.tx = tx;
        this.ty = ty;
    }

    /**
     * Builds the pixel-to-uv mapping of one texture solution.
     *
     * @param width raster width in pixels
     * @param height raster height in pixels
     * @param uvToPixel the 2x3 row-major uv-to-pixel affine, as published by the host
     * @return the inverse mapping, or the normalized-raster fallback for a singular affine
     * @throws IllegalArgumentException when the raster extent is not positive or the affine is
     *     not a 2x3 matrix
     */
    public static TextureUvMapping of(final int width, final int height, final double[] uvToPixel) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("raster extent must be positive");
        }
        if (uvToPixel == null || uvToPixel.length != UV_TO_PIXEL_LENGTH) {
            throw new IllegalArgumentException("uv-to-pixel affine must have " + UV_TO_PIXEL_LENGTH + " elements");
        }
        final double m00 = uvToPixel[0];
        final double m01 = uvToPixel[1];
        final double m02 = uvToPixel[2];
        final double m10 = uvToPixel[3];
        final double m11 = uvToPixel[4];
        final double m12 = uvToPixel[5];
        final double determinant = m00 * m11 - m01 * m10;
        if (!(Math.abs(determinant) > SINGULAR_DETERMINANT)) {
            return normalized(width, height);
        }
        final double a = m11 / determinant;
        final double b = -m01 / determinant;
        final double c = -m10 / determinant;
        final double d = m00 / determinant;
        final double tx = (m01 * m12 - m11 * m02) / determinant;
        final double ty = (m10 * m02 - m00 * m12) / determinant;
        return new TextureUvMapping(a, b, c, d, tx, ty);
    }

    /**
     * Maps one raster pixel coordinate into uv space.
     *
     * @param pixelX raster column
     * @param pixelY raster row
     * @return the uv sample that reads this pixel through the host affine
     */
    public Point2 map(final double pixelX, final double pixelY) {
        return new Point2((float) (a * pixelX + b * pixelY + tx), (float) (c * pixelX + d * pixelY + ty));
    }

    private static TextureUvMapping normalized(final int width, final int height) {
        return new TextureUvMapping(1.0 / width, 0.0, 0.0, 1.0 / height, 0.0, 0.0);
    }
}
