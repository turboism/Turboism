package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelImageId;
import java.util.Objects;

/**
 * Placement of one model image on an appended texture atlas page.
 *
 * @param modelImageId the unique identity of the model image to place
 * @param pageIndex the zero-based index of the appended page to place onto
 * @param x the non-negative horizontal pixel offset on the page
 * @param y the non-negative vertical pixel offset on the page
 * @param width the positive pixel width of the placed region
 * @param height the positive pixel height of the placed region
 */
@CubismEditor(from = "5.0.00")
public record ModelImagePlacement(
        ModelImageId modelImageId,
        int pageIndex,
        int x,
        int y,
        int width,
        int height) {

    public ModelImagePlacement {
        Objects.requireNonNull(modelImageId, "modelImageId");
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must not be negative: " + pageIndex);
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("placement coordinates must not be negative: x=" + x + ", y=" + y);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "placement dimensions must be positive: width=" + width + ", height=" + height);
        }
    }
}
