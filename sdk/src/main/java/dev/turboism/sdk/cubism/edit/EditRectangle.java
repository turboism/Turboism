package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;

/**
 * One grid rectangle of a warp deformer: the four corner positions of a lattice cell.
 *
 * @param corners the four corner points in lattice order; exactly four entries
 */
public record EditRectangle(List<Point2> corners) {

    public EditRectangle {
        corners = List.copyOf(Objects.requireNonNull(corners, "corners"));
        if (corners.size() != 4) {
            throw new IllegalArgumentException("rectangle requires exactly four corners");
        }
    }
}
