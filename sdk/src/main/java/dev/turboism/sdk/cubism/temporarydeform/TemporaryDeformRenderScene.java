package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.ui.appearance.UiColor;
import java.util.List;
import java.util.Objects;

/**
 * Immutable overlay geometry submitted by a {@link TemporaryDeformSession} for canvas rendering.
 *
 * @param lineLayers polyline layers rendered under point handles
 * @param pointLayers point handle layers rendered on top of line layers
 */
@CubismEditor(from = "5.0.00")
public record TemporaryDeformRenderScene(List<LineLayer> lineLayers, List<PointLayer> pointLayers) {

    public static final TemporaryDeformRenderScene EMPTY = new TemporaryDeformRenderScene(List.of(), List.of());

    public TemporaryDeformRenderScene {
        lineLayers = List.copyOf(Objects.requireNonNull(lineLayers, "lineLayers"));
        lineLayers.forEach(layer -> Objects.requireNonNull(layer, "lineLayer"));
        pointLayers = List.copyOf(Objects.requireNonNull(pointLayers, "pointLayers"));
        pointLayers.forEach(layer -> Objects.requireNonNull(layer, "pointLayer"));
    }

    /** One uniformly styled polyline batch rendered on the modeling canvas. */
    @CubismEditor(from = "5.0.00")
    public record LineLayer(List<List<Point2>> polylines, UiColor color, float width) {
        public LineLayer {
            Objects.requireNonNull(polylines, "polylines");
            polylines = polylines.stream()
                    .map(line -> List.copyOf(Objects.requireNonNull(line, "polyline")))
                    .toList();
            color = Objects.requireNonNull(color, "color");
            if (!Float.isFinite(width) || width <= 0.0F) {
                throw new IllegalArgumentException("line width must be finite and positive");
            }
        }
    }

    /** One uniformly styled control-point or handle batch rendered on the modeling canvas. */
    @CubismEditor(from = "5.0.00")
    public record PointLayer(List<Point2> points, int pixelSize, UiColor fillColor, UiColor borderColor) {
        public PointLayer {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            points.forEach(point -> Objects.requireNonNull(point, "point"));
            if (pixelSize <= 0) {
                throw new IllegalArgumentException("pixelSize must be positive");
            }
            fillColor = Objects.requireNonNull(fillColor, "fillColor");
            borderColor = Objects.requireNonNull(borderColor, "borderColor");
        }
    }
}
