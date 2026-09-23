package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.Objects;

/**
 * The bounding rectangle of a warp deformer, matching the official {@code Rectangle} of the
 * {@code GetObject} {@code WarpDeformer} data block (external API 1.1.0): four named corner
 * positions. The editor reports corners in a Y-down lattice space where {@code topLeft} is
 * the minimum corner and {@code bottomRight} the maximum.
 *
 * @param topLeft top-left corner position
 * @param bottomLeft bottom-left corner position
 * @param topRight top-right corner position
 * @param bottomRight bottom-right corner position
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditRectangle(
        Point2 topLeft,
        Point2 bottomLeft,
        Point2 topRight,
        Point2 bottomRight) {

    public EditRectangle {
        Objects.requireNonNull(topLeft, "topLeft");
        Objects.requireNonNull(bottomLeft, "bottomLeft");
        Objects.requireNonNull(topRight, "topRight");
        Objects.requireNonNull(bottomRight, "bottomRight");
    }
}
