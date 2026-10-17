package dev.turboism.plugin.frameanimation.service;

import dev.turboism.plugin.frameanimation.domain.AnchorPoint;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;

/**
 * Constructs standard quadrilateral ArtMesh geometry for frame animation meshes.
 */
public final class FrameMeshAssembler {

    private FrameMeshAssembler() {}

    /**
     * Builds the source geometry corresponding 1:1 to the image's raster plane [0, width] x [0, height].
     * This eliminates phase shift when Cubism registers and samples the underlying texture.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param flipVertical true to invert vertical UV mapping
     * @return normalized source geometry aligned to the image pixel rectangle
     */
    public static ArtMeshGeometry createSourceGeometry(
            final float width, final float height, final boolean flipVertical) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("dimensions must be positive: " + width + "x" + height);
        }

        // Texture plane positions matching physical image bounds exactly (0,0 to width,height)
        final List<Point2> positions = List.of(
                new Point2(0.0f, 0.0f), new Point2(width, 0.0f), new Point2(width, height), new Point2(0.0f, height));

        final List<Point2> uvs;
        if (!flipVertical) {
            uvs = List.of(
                    new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f));
        } else {
            uvs = List.of(
                    new Point2(0.0f, 1.0f), new Point2(1.0f, 1.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 0.0f));
        }

        final List<Integer> triangleIndices = List.of(0, 1, 2, 0, 2, 3);
        return new ArtMeshGeometry(positions, uvs, triangleIndices);
    }

    /**
     * Calculates the visible ArtMesh vertex coordinates placed on the Cubism canvas.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param anchor placement anchor point
     * @param centerX center X coordinate on canvas
     * @param centerY center Y coordinate on canvas
     * @return list of 4 vertex positions in standard quadrilateral order
     */
    public static List<Point2> createDefaultPositions(
            final float width, final float height, final AnchorPoint anchor, final float centerX, final float centerY) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("dimensions must be positive: " + width + "x" + height);
        }
        Objects.requireNonNull(anchor, "anchor");

        final float ox = width * anchor.anchorU();
        final float oy = height * anchor.anchorV();

        final float left = centerX - ox;
        final float right = left + width;
        final float top = centerY - oy;
        final float bottom = top + height;

        return List.of(
                new Point2(left, top), new Point2(right, top), new Point2(right, bottom), new Point2(left, bottom));
    }

    /**
     * Compatibility helper returning ArtMeshGeometry combining source and default placements.
     */
    public static ArtMeshGeometry createGeometry(
            final float width, final float height, final AnchorPoint anchor, final float centerX, final float centerY) {
        return createGeometry(width, height, anchor, centerX, centerY, false);
    }

    /**
     * Compatibility helper returning ArtMeshGeometry combining source and default placements.
     */
    public static ArtMeshGeometry createGeometry(
            final float width,
            final float height,
            final AnchorPoint anchor,
            final float centerX,
            final float centerY,
            final boolean flipVertical) {
        final List<Point2> positions = createDefaultPositions(width, height, anchor, centerX, centerY);
        final ArtMeshGeometry src = createSourceGeometry(width, height, flipVertical);
        return new ArtMeshGeometry(positions, src.uvs(), src.triangleIndices());
    }
}
