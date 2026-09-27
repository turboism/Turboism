package dev.turboism.adapter.cubism.warp;

import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direction semantics and structural symmetry of the index-symmetric pairing:
 * the named side is always the source copied across the grid's centre fold onto
 * the opposite side, and every rewritten point equals the reflection of its
 * structural counterpart — regardless of how irregular the grid is.
 */
final class WarpMirrorPairingTest {

    private static final float EPS = 0.0001f;

    /**
     * 3x3 point grid (2x2 divisions) spanning [0,20]² with a per-column y skew so the
     * halves are asymmetric: column c sits at x=c*10, row r at y=r*10+c.
     */
    private static float[] skewedGrid() {
        final float[] positions = new float[3 * 3 * 2];
        int offset = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                positions[offset++] = col * 10f;
                positions[offset++] = row * 10f + col;
            }
        }
        return positions;
    }

    /**
     * Irregular 4-column grid (3 divisions): column x positions are
     * [0, 3, 5.5, 11] — the bounding-box centre (5.5+... → 5.5 wait, (0+11)/2=5.5)
     * differs from the centre seam midpoint ((3+5.5)/2 = 4.25), the case the
     * legacy nearest-source pairing got wrong.
     */
    private static float[] irregularGrid() {
        final float[] xs = {0f, 3f, 5.5f, 11f};
        final float[] positions = new float[4 * 2 * 2];
        int offset = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                positions[offset++] = xs[col];
                positions[offset++] = row * 8f + col;
            }
        }
        return positions;
    }

    private static float x(final float[] positions, final int cols, final int col, final int row) {
        return positions[(row * cols + col) * 2];
    }

    private static float y(final float[] positions, final int cols, final int col, final int row) {
        return positions[(row * cols + col) * 2 + 1];
    }

    private static float x(final float[] positions, final int col, final int row) {
        return x(positions, 3, col, row);
    }

    private static float y(final float[] positions, final int col, final int row) {
        return y(positions, 3, col, row);
    }

    @Test
    void leftToRightCopiesTheLeftHalfOntoTheRight() {
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(skewedGrid(), 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        for (int row = 0; row < 3; row++) {
            // Right column takes the reflection of the left column (y skew 0).
            assertEquals(20f, x(result.positions(), 2, row), EPS);
            assertEquals(row * 10f, y(result.positions(), 2, row), EPS);
            // Source column and centre column are untouched.
            assertEquals(row * 10f + 0f, y(result.positions(), 0, row), EPS);
            assertEquals(row * 10f + 1f, y(result.positions(), 1, row), EPS);
        }
        assertEquals(3, result.pairedCount());
    }

    @Test
    void rightToLeftCopiesTheRightHalfOntoTheLeft() {
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(skewedGrid(), 3, 3, WarpMirrorDirection.RIGHT_TO_LEFT);
        assertNotNull(result);
        for (int row = 0; row < 3; row++) {
            assertEquals(0f, x(result.positions(), 0, row), EPS);
            // Left column takes the right column's skew (col=2 → +2).
            assertEquals(row * 10f + 2f, y(result.positions(), 0, row), EPS);
            assertEquals(row * 10f + 2f, y(result.positions(), 2, row), EPS);
        }
    }

    @Test
    void topToBottomCopiesTheTopHalfOntoTheBottom() {
        // Rows: y = r*10; x = c*10 + r skews by row so top/bottom differ.
        final float[] grid = new float[3 * 3 * 2];
        int offset = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                grid[offset++] = col * 10f + row;
                grid[offset++] = row * 10f;
            }
        }
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(grid, 3, 3, WarpMirrorDirection.TOP_TO_BOTTOM);
        assertNotNull(result);
        for (int col = 0; col < 3; col++) {
            // Bottom row takes the reflection of the top row (y=0 → y=20, x skew +0).
            assertEquals(col * 10f, x(result.positions(), col, 2), EPS);
            assertEquals(20f, y(result.positions(), col, 2), EPS);
        }
    }

    @Test
    void bottomToTopCopiesTheBottomHalfOntoTheTop() {
        final float[] grid = new float[3 * 3 * 2];
        int offset = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                grid[offset++] = col * 10f + row;
                grid[offset++] = row * 10f;
            }
        }
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(grid, 3, 3, WarpMirrorDirection.BOTTOM_TO_TOP);
        assertNotNull(result);
        for (int col = 0; col < 3; col++) {
            // Top row takes the bottom row's x skew (+2) reflected to y=0.
            assertEquals(col * 10f + 2f, x(result.positions(), col, 0), EPS);
            assertEquals(0f, y(result.positions(), col, 0), EPS);
        }
    }

    @Test
    void irregularGridMirrorsExactlyAcrossTheCentreSeam() {
        // xs = [0, 3, 5.5, 11]; seam axis = (3 + 5.5)/2 = 4.25.
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(irregularGrid(), 4, 2, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        assertEquals(4, result.pairedCount());
        for (int row = 0; row < 2; row++) {
            // col2 := reflect(col1) = 2*4.25 - 3 = 5.5 (already there — seam anchored);
            // col3 := reflect(col0) = 2*4.25 - 0 = 8.5 (was 11 — pulled to the mirror).
            assertEquals(5.5f, x(result.positions(), 4, 2, row), EPS);
            assertEquals(8.5f, x(result.positions(), 4, 3, row), EPS);
            // y mirrors verbatim from the structural counterpart.
            assertEquals(row * 8f + 1f, y(result.positions(), 4, 2, row), EPS);
            assertEquals(row * 8f + 0f, y(result.positions(), 4, 3, row), EPS);
        }
    }

    @Test
    void irregularGridMirroredResultIsExactlySymmetric() {
        for (WarpMirrorDirection direction : WarpMirrorDirection.values()) {
            final float[] grid = irregularGrid();
            final WarpMirrorPairing.Result first =
                WarpMirrorPairing.mirror(grid, 4, 2, direction);
            assertNotNull(first);
            // Mirroring the result a second time must be a no-op: the output is
            // already symmetric across the fold, by construction.
            final WarpMirrorPairing.Result second =
                WarpMirrorPairing.mirror(first.positions(), 4, 2, direction);
            assertNotNull(second);
            for (int index = 0; index < grid.length; index++) {
                assertEquals(first.positions()[index], second.positions()[index], EPS,
                    direction + " second pass changed index " + index);
            }
        }
    }

    @Test
    void centreLinePointsAreSkippedByIndex() {
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(skewedGrid(), 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        // The middle column is the fold line; it must not be rewritten.
        for (int row = 0; row < 3; row++) {
            assertEquals(10f, x(result.positions(), 1, row), EPS);
        }
    }

    @Test
    void flippedStorageOrderStillMirrorsTheNamedSide() {
        // Flat column 0 sits at visual RIGHT (x=20), flat column 2 at visual
        // LEFT (x=0): storage order runs opposite to the canvas. The direction
        // names a spatial side, so LEFT_TO_RIGHT must still copy the low-x
        // (visual-left) half onto the high-x half.
        final float[] flipped = new float[] {
            20f, 100f, 10f, 50f, 0f, 0f,
            20f, 110f, 10f, 55f, 0f, 5f
        };
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(flipped, 3, 2, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        // Axis = centre-column mean = 10. Visual-left source (flat col 2)
        // reflects onto flat col 0: (0,y) -> (20,y).
        assertEquals(20f, x(result.positions(), 3, 0, 0), EPS);
        assertEquals(0f, y(result.positions(), 3, 0, 0), EPS);
        assertEquals(20f, x(result.positions(), 3, 0, 1), EPS);
        assertEquals(5f, y(result.positions(), 3, 0, 1), EPS);
        // Source column (flat col 2) and centre column stay untouched.
        assertEquals(0f, x(result.positions(), 3, 2, 0), EPS);
        assertEquals(10f, x(result.positions(), 3, 1, 0), EPS);
    }

    @Test
    void collapsedGridProducesUnchangedOutput() {
        // Every point shares x=5: the fold sits on all points; the copy writes
        // identical values — a Result that the caller reports as NO_CHANGE.
        final float[] onAxis = new float[] {
            5f, 0f, 5f, 0f,
            5f, 10f, 5f, 10f
        };
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(onAxis, 2, 2, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        for (int index = 0; index < onAxis.length; index++) {
            assertEquals(onAxis[index], result.positions()[index], EPS);
        }
    }

    @Test
    void returnsNullForDegenerateTopology() {
        assertNull(WarpMirrorPairing.mirror(null, 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT));
        assertNull(WarpMirrorPairing.mirror(new float[4], 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT));
        assertNull(WarpMirrorPairing.mirror(skewedGrid(), 1, 3, WarpMirrorDirection.LEFT_TO_RIGHT));
        // Non-finite positions.
        final float[] nan = skewedGrid();
        nan[2] = Float.NaN;
        assertNull(WarpMirrorPairing.mirror(nan, 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT));
    }

    @Test
    void alreadySymmetricGridStillReportsPairs() {
        // A grid whose right half already equals the mirrored left half.
        final float[] symmetric = new float[] {
            0f, 0f, 10f, 0f, 20f, 0f,
            0f, 5f, 10f, 5f, 20f, 5f,
            0f, 10f, 10f, 10f, 20f, 10f
        };
        final WarpMirrorPairing.Result result =
            WarpMirrorPairing.mirror(symmetric, 3, 3, WarpMirrorDirection.LEFT_TO_RIGHT);
        assertNotNull(result);
        assertEquals(3, result.pairedCount());
        // Caller-level NO_CHANGE check: output equals input.
        for (int index = 0; index < symmetric.length; index++) {
            assertTrue(Math.abs(symmetric[index] - result.positions()[index]) <= EPS);
        }
    }
}
