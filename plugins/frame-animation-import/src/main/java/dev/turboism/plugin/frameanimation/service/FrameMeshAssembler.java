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
     * Builds a quadrilateral ArtMeshGeometry with upright orientation on Cubism canvas.
     */
    public static ArtMeshGeometry createGeometry(
            final float width, final float height, final AnchorPoint anchor, final float centerX, final float centerY) {
        return createGeometry(width, height, anchor, centerX, centerY, false);
    }

    /**
     * Builds a quadrilateral ArtMeshGeometry with optional vertical flip.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param anchor placement anchor point
     * @param centerX center X offset on canvas
     * @param centerY center Y offset on canvas
     * @param flipVertical true to flip vertically
     * @return validated immutable ArtMeshGeometry
     */
    public static ArtMeshGeometry createGeometry(
            final float width,
            final float height,
            final AnchorPoint anchor,
            final float centerX,
            final float centerY,
            final boolean flipVertical) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("dimensions must be positive: " + width + "x" + height);
        }
        Objects.requireNonNull(anchor, "anchor");

        final float ox = width * anchor.anchorU();
        final float oy = height * anchor.anchorV();

        final float left = centerX - ox;
        final float right = left + width;
        final float bottom = centerY - oy;
        final float top = bottom + height;

        // Quad vertices (CCW order: top-left, top-right, bottom-right, bottom-left)
        final List<Point2> positions = List.of(
                new Point2(left, bottom), new Point2(right, bottom), new Point2(right, top), new Point2(left, top));

        final List<Point2> uvs;
        if (!flipVertical) {
            // Normal upright mapping in Cubism coordinate space
            uvs = List.of(
                    new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f));
        } else {
            // Inverted mapping
            uvs = List.of(
                    new Point2(0.0f, 1.0f), new Point2(1.0f, 1.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 0.0f));
        }

        final List<Integer> triangleIndices = List.of(0, 1, 2, 0, 2, 3);

        return new ArtMeshGeometry(positions, uvs, triangleIndices);
    }
}
