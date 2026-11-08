package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/**
 * The ARGB pixel raster of the texture input an ArtMesh samples: the model image (PSD-layer
 * composite) when the project has no texture atlas, or the atlas tile otherwise, together with
 * the {@link #uvToPixel()} affine that converts a mesh uv into raster coordinates. The mesh's
 * {@code uvs()} are normalized over the host's cached-image canvas — a mipmap-aligned extent
 * that can differ from the raster's own {@code width} x {@code height} — so {@code u * width}
 * is only correct when {@code uvToPixel} is the identity. Apply it as
 * {@code px = m00*u + m01*v + m02, py = m10*u + m11*v + m12} and clamp to the raster.
 * {@code argb} is row-major {@code width * height} in {@code 0xAARRGGBB} format.
 *
 * <p>Empty when the host cannot resolve the drawable's texture input or decode its pixels.</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03", "5.3.04"})
public record TexturePixels(int width, int height, int[] argb, double[] uvToPixel) {

    /**
     * Compatibility constructor for callers that predate {@link #uvToPixel()}: assumes uvs
     * index this raster normalized, which holds only for unpadded cached images.
     */
    public TexturePixels(final int width, final int height, final int[] argb) {
        this(width, height, argb, null);
    }

    public TexturePixels {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("texture pixel extent must be positive");
        }
        if (argb == null || (long) argb.length < (long) width * height) {
            throw new IllegalArgumentException("texture pixel raster is smaller than the image");
        }
        if (uvToPixel == null) {
            uvToPixel = new double[] {width, 0.0, 0.0, 0.0, height, 0.0};
        }
        if (uvToPixel.length != 6) {
            throw new IllegalArgumentException("uv-to-pixel affine must have 6 elements");
        }
        uvToPixel = uvToPixel.clone();
    }

    /**
     * Maps one normalized mesh uv to raster pixel coordinates. {@code out} receives
     * {@code (px, py)}; callers clamp to {@code [0, width) x [0, height)} — uv coordinates in
     * the cached-image padding legitimately land outside the raster.
     */
    public void uvToPixel(final double u, final double v, final double[] out) {
        final double[] m = uvToPixel;
        out[0] = m[0] * u + m[1] * v + m[2];
        out[1] = m[3] * u + m[4] * v + m[5];
    }
}
