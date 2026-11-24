package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts closed silhouette rings with bleed expansion in source-image pixel coordinates.
 *
 * <p>Provides a shared boundary-extraction entry point for adaptive placement so bleed dilation
 * and curvature-sampled outline rings follow one code path.</p>
 */
public final class SilhouetteOutline {

    /**
     * Extracted silhouette rings and bounding metrics in source pixel coordinates.
     *
     * @param rings closed rings in source pixel coordinates, islands and holes included
     * @param silhouetteSpan longest bounding-box extent of solid artwork pixels
     * @param imageWidth source image width
     * @param imageHeight source image height
     */
    public record Result(List<List<Point2>> rings, double silhouetteSpan, int imageWidth, int imageHeight) {}

    private SilhouetteOutline() {}

    /**
     * Extracts bleed-expanded silhouette boundary rings from an ARGB raster.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @param width image width in pixels
     * @param height image height in pixels
     * @param bleedMarginPx transparent bleed expansion in pixels
     * @param maxSpacingPx maximum boundary segment length in pixels
     * @param tolerancePx curvature chord tolerance in pixels
     * @param minSpacingPx minimum boundary point spacing in pixels
     * @return extracted rings in source pixel coordinates, or {@code null} when outline fails to close
     */
    public static Result extract(
            final int[] argb,
            final int width,
            final int height,
            final double bleedMarginPx,
            final double maxSpacingPx,
            final double tolerancePx,
            final double minSpacingPx) {
        final RegionRaster raster = RegionRaster.cropped(argb, width, height, bleedMarginPx);
        final boolean[] domain = raster.domainMask();
        final int[] labels = new int[domain.length];
        for (int i = 0; i < domain.length; i++) {
            labels[i] = domain[i] ? 1 : 0;
        }
        final List<BoundaryPath> borders = SharedBoundaryPaths.extract(
                labels, raster.width(), raster.height(), tolerancePx, minSpacingPx, maxSpacingPx);
        final PolygonShape shape = DomainOutline.of(borders);
        if (shape == null) {
            return null;
        }
        final int originX = raster.originX();
        final int originY = raster.originY();
        final List<List<Point2>> rings = new ArrayList<>(shape.rings().size());
        for (final Polyline ring : shape.rings()) {
            final List<Point2> points = new ArrayList<>(ring.size());
            for (int i = 0; i < ring.size(); i++) {
                points.add(new Point2((float) (ring.x(i) + originX), (float) (ring.y(i) + originY)));
            }
            if (points.size() >= 3) {
                rings.add(List.copyOf(points));
            }
        }
        if (rings.isEmpty()) {
            return null;
        }
        return new Result(List.copyOf(rings), raster.silhouetteSpan(), width, height);
    }
}
