package dev.turboism.validation.warpaltsymmetry;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline self-check for the probe's pure measurement code. It is compiled and run by
 * {@code build.sh} against the compiled plugin classes but is deliberately excluded from
 * the packaged probe JAR.
 *
 * <p>The mirror measurement is the only part of the probe that turns an observation into a
 * number, so it is pinned here before any host run: a mirror-symmetric move must count every
 * moved pair as mirrored, an asymmetric move must not, a static grid must report no movement,
 * and a column that is its own mirror (even division count) must be counted separately rather
 * than as a pair.</p>
 */
public final class WarpAltSymmetrySelfCheck {

    private static final float EPSILON = 1.0e-4f;

    private WarpAltSymmetrySelfCheck() { }

    public static void main(final String[] args) {
        checkUnchanged();
        checkMirroredMove();
        checkAsymmetricMove();
        checkSelfMirroredColumn();
        checkReportOrdering();
        System.out.println("WARP_ALT_SELFCHECK_OK");
    }

    private static void checkUnchanged() {
        final WarpGrid grid = uniformGrid(4, 4);
        final WarpDeformerAltSymmetryHostValidationPlugin.Symmetry symmetry =
            WarpDeformerAltSymmetryHostValidationPlugin.analyseMirror(grid, grid);
        require(symmetry.moved() == 0, "unchanged grid reported movement");
        require(symmetry.pairsMoved() == 0, "unchanged grid reported moved pairs");
        require(symmetry.pairsMirrored() == 0, "unchanged grid reported mirrored pairs");
    }

    private static void checkMirroredMove() {
        final WarpGrid before = uniformGrid(4, 4);
        final List<Point2> points = new ArrayList<>(before.controlPoints());
        final int width = before.columns() + 1;
        // Move the left column outward by (-3, +1) and the mirrored right column by (+3, +1).
        for (int row = 0; row <= before.rows(); row++) {
            points.set(row * width, shift(points.get(row * width), -3.0f, 1.0f));
            points.set(row * width + before.columns(),
                shift(points.get(row * width + before.columns()), 3.0f, 1.0f));
        }
        final WarpGrid after = new WarpGrid(before.rows(), before.columns(), false, points);
        final WarpDeformerAltSymmetryHostValidationPlugin.Symmetry symmetry =
            WarpDeformerAltSymmetryHostValidationPlugin.analyseMirror(before, after);
        require(symmetry.moved() == 10, "expected 10 moved points, got " + symmetry.moved());
        require(symmetry.pairsMoved() == 10, "expected 10 moved pairs, got " + symmetry.pairsMoved());
        require(symmetry.pairsMirrored() == 10,
            "expected 10 mirrored pairs, got " + symmetry.pairsMirrored());
        require(Math.abs(symmetry.axisX() - 2.0f) < EPSILON,
            "unexpected axis X " + symmetry.axisX());
    }

    private static void checkAsymmetricMove() {
        final WarpGrid before = uniformGrid(4, 4);
        final List<Point2> points = new ArrayList<>(before.controlPoints());
        points.set(0, shift(points.get(0), -3.0f, 0.0f));
        final WarpGrid after = new WarpGrid(before.rows(), before.columns(), false, points);
        final WarpDeformerAltSymmetryHostValidationPlugin.Symmetry symmetry =
            WarpDeformerAltSymmetryHostValidationPlugin.analyseMirror(before, after);
        require(symmetry.moved() == 1, "expected 1 moved point, got " + symmetry.moved());
        require(symmetry.pairsMoved() == 0, "lone move must not count as a pair");
        require(symmetry.pairsMirrored() == 0, "lone move must not count as mirrored");
    }

    private static void checkSelfMirroredColumn() {
        // An even division count leaves a middle column that is its own mirror.
        final WarpGrid before = uniformGrid(4, 4);
        final List<Point2> points = new ArrayList<>(before.controlPoints());
        final int width = before.columns() + 1;
        final int middle = before.columns() / 2;
        points.set(middle, shift(points.get(middle), 2.0f, 0.0f));
        points.set(width + middle, shift(points.get(width + middle), 0.0f, 2.0f));
        final WarpGrid after = new WarpGrid(before.rows(), before.columns(), false, points);
        final WarpDeformerAltSymmetryHostValidationPlugin.Symmetry symmetry =
            WarpDeformerAltSymmetryHostValidationPlugin.analyseMirror(before, after);
        require(symmetry.moved() == 2, "expected 2 moved points, got " + symmetry.moved());
        require(symmetry.selfMirrored() == 2,
            "expected 2 self-mirrored points, got " + symmetry.selfMirrored());
        require(symmetry.pairsMoved() == 0, "self-mirrored points are not pairs");
    }

    private static void checkReportOrdering() {
        final WarpDeformerAltSymmetryHostValidationPlugin.Report report =
            new WarpDeformerAltSymmetryHostValidationPlugin.Report();
        report.put("b", "1");
        report.put("a", "2");
        report.put("b", "3");
        require("b=3\na=2\n".equals(report.render()),
            "report must keep first-insertion order and overwrite in place: " + report.render());
    }

    private static WarpGrid uniformGrid(final int rows, final int columns) {
        final List<Point2> points = new ArrayList<>();
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column <= columns; column++) {
                points.add(new Point2(column, row));
            }
        }
        return new WarpGrid(rows, columns, false, points);
    }

    private static Point2 shift(final Point2 point, final float deltaX, final float deltaY) {
        return new Point2(point.x() + deltaX, point.y() + deltaY);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
