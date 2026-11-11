package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;

/**
 * Maps a layout uv sample into one stored ArtMesh form's own position space.
 *
 * <p>The runtime cannot load plugin classes, and the plugin-side mapper carries a frozen
 * coordinate contract, so this class mirrors that mapper: barycentric interpolation inside the
 * form's own triangles, then a least-squares affine fit for samples outside every triangle. Both
 * paths read the mesh source uvs and triangles together with one form's positions, so a derived
 * vertex keeps that form's deformation instead of the current keyform's shape.</p>
 *
 * <p>{@link #map} throws {@link IllegalArgumentException} when a sample resolves outside the
 * finite coordinate domain. Callers translate that signal into a refusal, because a form that
 * cannot receive the layout must not be written partially.</p>
 */
final class AutoMeshUvMapping {

    /** Slack that keeps a sample on a shared triangle edge inside both neighbours. */
    private static final double TRIANGLE_BOUNDS_SLACK = 1.0e-6;

    /** Determinant magnitude below which a triangle carries no barycentric weights. */
    private static final double SINGULAR_TRIANGLE = 1.0e-12;

    /** Lowest barycentric weight that still counts as inside a triangle. */
    private static final double EDGE_WEIGHT = -1.0e-4;

    /** Pivot magnitude below which the normal equations have no unique solution. */
    private static final double SINGULAR_SYSTEM = 1.0e-14;

    /** Axis span below which the bounds fallback cannot scale one axis onto the other. */
    private static final double COLLAPSED_SPAN = 1.0e-9;

    /** Fewest correspondences a least-squares affine fit can solve. */
    private static final int MINIMUM_FIT_CORRESPONDENCES = 3;

    /** Vertices one triangle names; every triangle block consumes this many indices. */
    private static final int TRIANGLE_VERTICES = 3;

    private final List<Point2> uvs;
    private final List<Point2> positions;
    private final List<Integer> indices;
    private final Affine affine;

    private AutoMeshUvMapping(
            final List<Point2> uvs, final List<Point2> positions, final List<Integer> indices, final Affine affine) {
        this.uvs = uvs;
        this.positions = positions;
        this.indices = indices;
        this.affine = affine;
    }

    /**
     * Builds the mapper of one form.
     *
     * @param uvs the mesh source texture coordinates
     * @param positions one form's own stored positions, in vertex order
     * @param indices the mesh source triangle indices
     * @return a mapper over that form
     */
    static AutoMeshUvMapping of(final List<Point2> uvs, final List<Point2> positions, final List<Integer> indices) {
        final List<Point2> checkedUvs = List.copyOf(Objects.requireNonNull(uvs, "uvs"));
        final List<Point2> checkedPositions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        final List<Integer> checkedIndices = List.copyOf(Objects.requireNonNull(indices, "indices"));
        return new AutoMeshUvMapping(
                checkedUvs, checkedPositions, checkedIndices, Affine.fit(checkedUvs, checkedPositions));
    }

    /**
     * Maps one uv sample into this form's position space.
     *
     * @param u horizontal texture coordinate
     * @param v vertical texture coordinate
     * @return the sample in this form's positions
     * @throws IllegalArgumentException when the sample resolves to a non-finite coordinate
     */
    Point2 map(final double u, final double v) {
        for (int block = 0; block + TRIANGLE_VERTICES <= indices.size(); block += TRIANGLE_VERTICES) {
            final Point2 first = uvs.get(indices.get(block));
            final Point2 second = uvs.get(indices.get(block + 1));
            final Point2 third = uvs.get(indices.get(block + 2));
            if (!insideBounds(u, v, first, second, third)) {
                continue;
            }
            final double determinant = (second.y() - third.y()) * (first.x() - third.x())
                    + (third.x() - second.x()) * (first.y() - third.y());
            if (Math.abs(determinant) < SINGULAR_TRIANGLE) {
                continue;
            }
            final double firstWeight =
                    ((second.y() - third.y()) * (u - third.x()) + (third.x() - second.x()) * (v - third.y()))
                            / determinant;
            final double secondWeight =
                    ((third.y() - first.y()) * (u - third.x()) + (first.x() - third.x()) * (v - third.y()))
                            / determinant;
            final double thirdWeight = 1.0 - firstWeight - secondWeight;
            if (firstWeight < EDGE_WEIGHT || secondWeight < EDGE_WEIGHT || thirdWeight < EDGE_WEIGHT) {
                continue;
            }
            final Point2 firstPosition = positions.get(indices.get(block));
            final Point2 secondPosition = positions.get(indices.get(block + 1));
            final Point2 thirdPosition = positions.get(indices.get(block + 2));
            return new Point2(
                    (float) (firstWeight * firstPosition.x()
                            + secondWeight * secondPosition.x()
                            + thirdWeight * thirdPosition.x()),
                    (float) (firstWeight * firstPosition.y()
                            + secondWeight * secondPosition.y()
                            + thirdWeight * thirdPosition.y()));
        }
        return affine.map(u, v);
    }

    private static boolean insideBounds(
            final double u, final double v, final Point2 first, final Point2 second, final Point2 third) {
        return u >= Math.min(first.x(), Math.min(second.x(), third.x())) - TRIANGLE_BOUNDS_SLACK
                && u <= Math.max(first.x(), Math.max(second.x(), third.x())) + TRIANGLE_BOUNDS_SLACK
                && v >= Math.min(first.y(), Math.min(second.y(), third.y())) - TRIANGLE_BOUNDS_SLACK
                && v <= Math.max(first.y(), Math.max(second.y(), third.y())) + TRIANGLE_BOUNDS_SLACK;
    }

    /** Least-squares affine {@code (u, v) -> (x, y)} fit over the form's own vertices. */
    private static final class Affine {

        private final double a;
        private final double b;
        private final double tx;
        private final double c;
        private final double d;
        private final double ty;

        private Affine(
                final double a, final double b, final double tx, final double c, final double d, final double ty) {
            this.a = a;
            this.b = b;
            this.tx = tx;
            this.c = c;
            this.d = d;
            this.ty = ty;
        }

        private static Affine fit(final List<Point2> uvs, final List<Point2> positions) {
            if (uvs.size() < MINIMUM_FIT_CORRESPONDENCES || uvs.size() != positions.size()) {
                return boundsFallback(uvs, positions);
            }
            double sumU = 0;
            double sumV = 0;
            double sumUU = 0;
            double sumUV = 0;
            double sumVV = 0;
            double sumX = 0;
            double sumY = 0;
            double sumUX = 0;
            double sumVX = 0;
            double sumUY = 0;
            double sumVY = 0;
            for (int index = 0; index < uvs.size(); index++) {
                final double u = uvs.get(index).x();
                final double v = uvs.get(index).y();
                final double x = positions.get(index).x();
                final double y = positions.get(index).y();
                sumU += u;
                sumV += v;
                sumUU += u * u;
                sumUV += u * v;
                sumVV += v * v;
                sumX += x;
                sumY += y;
                sumUX += u * x;
                sumVX += v * x;
                sumUY += u * y;
                sumVY += v * y;
            }
            final double count = uvs.size();
            final double[] xRow = solve(new double[][] {
                {sumUU, sumUV, sumU, sumUX},
                {sumUV, sumVV, sumV, sumVX},
                {sumU, sumV, count, sumX}
            });
            final double[] yRow = solve(new double[][] {
                {sumUU, sumUV, sumU, sumUY},
                {sumUV, sumVV, sumV, sumVY},
                {sumU, sumV, count, sumY}
            });
            if (xRow == null || yRow == null) {
                return boundsFallback(uvs, positions);
            }
            return new Affine(xRow[0], xRow[1], xRow[2], yRow[0], yRow[1], yRow[2]);
        }

        private static Affine boundsFallback(final List<Point2> uvs, final List<Point2> positions) {
            if (uvs.isEmpty()) {
                return new Affine(1, 0, 0, 0, 1, 0);
            }
            double minimumU = Double.MAX_VALUE;
            double maximumU = -Double.MAX_VALUE;
            double minimumV = Double.MAX_VALUE;
            double maximumV = -Double.MAX_VALUE;
            double minimumX = Double.MAX_VALUE;
            double maximumX = -Double.MAX_VALUE;
            double minimumY = Double.MAX_VALUE;
            double maximumY = -Double.MAX_VALUE;
            for (int index = 0; index < uvs.size(); index++) {
                minimumU = Math.min(minimumU, uvs.get(index).x());
                maximumU = Math.max(maximumU, uvs.get(index).x());
                minimumV = Math.min(minimumV, uvs.get(index).y());
                maximumV = Math.max(maximumV, uvs.get(index).y());
                minimumX = Math.min(minimumX, positions.get(index).x());
                maximumX = Math.max(maximumX, positions.get(index).x());
                minimumY = Math.min(minimumY, positions.get(index).y());
                maximumY = Math.max(maximumY, positions.get(index).y());
            }
            final double spanU = maximumU - minimumU;
            final double spanV = maximumV - minimumV;
            final double a = spanU > COLLAPSED_SPAN ? (maximumX - minimumX) / spanU : 0;
            final double d = spanV > COLLAPSED_SPAN ? (maximumY - minimumY) / spanV : 0;
            return new Affine(a, 0, minimumX - a * minimumU, 0, d, minimumY - d * minimumV);
        }

        private Point2 map(final double u, final double v) {
            return new Point2((float) (a * u + b * v + tx), (float) (c * u + d * v + ty));
        }

        private static double[] solve(final double[][] matrix) {
            for (int column = 0; column < TRIANGLE_VERTICES; column++) {
                int pivot = column;
                for (int row = column + 1; row < TRIANGLE_VERTICES; row++) {
                    if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) {
                        pivot = row;
                    }
                }
                if (Math.abs(matrix[pivot][column]) < SINGULAR_SYSTEM) {
                    return null;
                }
                final double[] swap = matrix[pivot];
                matrix[pivot] = matrix[column];
                matrix[column] = swap;
                for (int row = column + 1; row < TRIANGLE_VERTICES; row++) {
                    final double factor = matrix[row][column] / matrix[column][column];
                    for (int term = column; term < TRIANGLE_VERTICES + 1; term++) {
                        matrix[row][term] -= factor * matrix[column][term];
                    }
                }
            }
            final double[] solved = new double[TRIANGLE_VERTICES];
            for (int row = TRIANGLE_VERTICES - 1; row >= 0; row--) {
                double accumulator = matrix[row][TRIANGLE_VERTICES];
                for (int term = row + 1; term < TRIANGLE_VERTICES; term++) {
                    accumulator -= matrix[row][term] * solved[term];
                }
                solved[row] = accumulator / matrix[row][row];
            }
            return solved;
        }
    }
}
