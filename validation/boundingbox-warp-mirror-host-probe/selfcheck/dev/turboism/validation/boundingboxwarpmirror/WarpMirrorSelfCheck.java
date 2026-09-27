package dev.turboism.validation.boundingboxwarpmirror;

import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.validation.boundingboxwarpmirror.BoundingBoxWarpMirrorHostValidationPlugin.DirectionCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline self-check for the probe's direction verifier: a grid mirrored
 * LEFT_TO_RIGHT must pass the LEFT_TO_RIGHT check and fail the RIGHT_TO_LEFT
 * check, so a swapped label can never produce a green run.
 */
public final class WarpMirrorSelfCheck {

    private WarpMirrorSelfCheck() { }

    public static void main(final String[] args) {
        // Horizontal test grid: x = col*10 (axis x=10 exact, middle column on-axis),
        // y = row*10 + col*3 so the two halves are distinguishable.
        final WarpGrid horizontalBefore = grid(false);
        final WarpGrid leftMirrored = mirrorHorizontally(horizontalBefore);
        assertCheck(verify(horizontalBefore, leftMirrored, WarpMirrorDirection.LEFT_TO_RIGHT), true);
        assertCheck(verify(horizontalBefore, leftMirrored, WarpMirrorDirection.RIGHT_TO_LEFT), false);

        // Vertical test grid: y = row*10 (axis y=10 exact, middle row on-axis),
        // x = col*10 + row*3 so the two halves are distinguishable.
        final WarpGrid verticalBefore = grid(true);
        final WarpGrid topMirrored = mirrorVertically(verticalBefore);
        assertCheck(verify(verticalBefore, topMirrored, WarpMirrorDirection.TOP_TO_BOTTOM), true);
        assertCheck(verify(verticalBefore, topMirrored, WarpMirrorDirection.BOTTOM_TO_TOP), false);

        // An unchanged grid produces mismatches (post-target side is not the
        // reflection of the source side), never a false pass.
        assertCheck(verify(horizontalBefore, horizontalBefore, WarpMirrorDirection.LEFT_TO_RIGHT), false);

        System.out.println("WarpMirrorSelfCheck: OK");
    }

    private static DirectionCheck verify(
        final WarpGrid before,
        final WarpGrid after,
        final WarpMirrorDirection direction
    ) {
        return BoundingBoxWarpMirrorHostValidationPlugin.verifyDirection(before, after, direction);
    }

    private static void assertCheck(final DirectionCheck check, final boolean shouldPass) {
        if (check.checked() == 0) {
            throw new IllegalStateException("no target-side points were checked");
        }
        if (shouldPass && check.mismatched() != 0) {
            throw new IllegalStateException("expected a clean check, got mismatched=" + check.mismatched());
        }
        if (!shouldPass && check.mismatched() == 0) {
            throw new IllegalStateException("expected mismatches but the check passed");
        }
    }

    private static WarpGrid grid(final boolean verticalFixture) {
        final List<Point2> points = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (verticalFixture) {
                    points.add(new Point2(col * 10f + row * 3f, row * 10f));
                } else {
                    points.add(new Point2(col * 10f, row * 10f + col * 3f));
                }
            }
        }
        return new WarpGrid(2, 2, false, points);
    }

    /** LEFT_TO_RIGHT: right half becomes the reflection of the left half about x=10. */
    private static WarpGrid mirrorHorizontally(final WarpGrid grid) {
        final List<Point2> points = new ArrayList<>(grid.controlPoints());
        for (int row = 0; row < 3; row++) {
            final Point2 source = grid.controlPoints().get(row * 3);
            points.set(row * 3 + 2, new Point2(20f - source.x(), source.y()));
        }
        return new WarpGrid(2, 2, false, points);
    }

    /** TOP_TO_BOTTOM: bottom half becomes the reflection of the top half about y=10. */
    private static WarpGrid mirrorVertically(final WarpGrid grid) {
        final List<Point2> points = new ArrayList<>(grid.controlPoints());
        for (int col = 0; col < 3; col++) {
            final Point2 source = grid.controlPoints().get(col);
            points.set(2 * 3 + col, new Point2(source.x(), 20f - source.y()));
        }
        return new WarpGrid(2, 2, false, points);
    }
}
