package dev.turboism.validation.modelimport3d.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Pure source-space fitting for a proven chart. Never changes source vertices or texture UVs.
 * Boundary controls use exact source samples; unidentifiable charts fail without regularization.
 * The chart's topology, native visibility and parameter interpolation require separate proofs. */
final class SurfaceWarpSpatialFit {
    private SurfaceWarpSpatialFit() {}

    static final int MAX_CELLS = 32, MAX_SAMPLES = 65535;

    record Sample(double u, double v, SurfaceProjection.Vertex position) {
        Sample {
            Objects.requireNonNull(position, "position");
            if (!Double.isFinite(u) || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1 || !finite(position))
                throw new IllegalArgumentException("finite chart sample required");
            if (u == 0) u = 0;
            if (v == 0) v = 0; // Canonicalize signed zero for chart identity.
        }
    }

    record Fit(int cells, List<SurfaceProjection.Vertex> controls, double maximumSourceResidual) {
        Fit {
            controls = List.copyOf(controls);
        }

        double projectedResidualBound(double scale) {
            if (!Double.isFinite(scale) || scale <= 0)
                throw new IllegalArgumentException("positive finite projection scale");
            double bound = maximumSourceResidual * scale;
            if (!Double.isFinite(bound)) throw new IllegalArgumentException("projected fit bound overflow");
            return bound;
        }

        SurfaceProjection.Vertex evaluate(double u, double v) {
            var basis = basis(u, v, cells);
            double x = 0, y = 0, z = 0;
            for (int i = 0; i < 4; i++) {
                var p = controls.get(basis.indices[i]);
                double w = basis.weights[i];
                x += w * p.x();
                y += w * p.y();
                z += w * p.z();
            }
            return new SurfaceProjection.Vertex(x, y, z);
        }
    }

    private record Parameter(double u, double v) {}

    private record Basis(int[] indices, double[] weights) {}

    static Fit fit(List<Sample> source, int cells) {
        Objects.requireNonNull(source, "source");
        if (cells < 1 || cells > MAX_CELLS || source.isEmpty() || source.size() > MAX_SAMPLES)
            throw new IllegalArgumentException("bounded chart samples/cells required");
        var unique = new LinkedHashMap<Parameter, Sample>();
        for (var sample : source) {
            Objects.requireNonNull(sample, "sample");
            var previous = unique.putIfAbsent(new Parameter(sample.u(), sample.v()), sample);
            if (previous != null && !previous.position().equals(sample.position()))
                throw new IllegalArgumentException("chart parameter maps to different source positions");
        }
        int side = cells + 1, count = side * side;
        var values = new SurfaceProjection.Vertex[count];
        var unknown = new int[count];
        java.util.Arrays.fill(unknown, -1);
        int interiorCount = 0;
        boolean allAligned = true;
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++) {
                int id = y * side + x;
                var exact = unique.get(new Parameter((double) x / cells, (double) y / cells));
                allAligned &= exact != null;
                if (x == 0 || y == 0 || x == cells || y == cells) {
                    if (exact == null)
                        throw new IllegalArgumentException("source samples missing an exact boundary control");
                    values[id] = exact.position();
                } else unknown[id] = interiorCount++;
            }
        if (allAligned && unique.size() == count) {
            for (int y = 0; y < side; y++)
                for (int x = 0; x < side; x++)
                    values[y * side + x] = unique.get(new Parameter((double) x / cells, (double) y / cells))
                            .position();
        } else if (interiorCount > 0) {
            // Translate before accumulation to retain accuracy for large source-world offsets.
            var origin = source.get(0).position();
            double[][] normal = new double[interiorCount][interiorCount], rhs = new double[interiorCount][3];
            for (var sample : unique.values()) {
                var b = basis(sample.u(), sample.v(), cells);
                double[] target = {
                    sample.position().x() - origin.x(),
                    sample.position().y() - origin.y(),
                    sample.position().z() - origin.z()
                };
                for (int k = 0; k < 4; k++)
                    if (unknown[b.indices[k]] < 0) {
                        var p = values[b.indices[k]];
                        double w = b.weights[k];
                        target[0] -= w * (p.x() - origin.x());
                        target[1] -= w * (p.y() - origin.y());
                        target[2] -= w * (p.z() - origin.z());
                    }
                for (int k = 0; k < 4; k++) {
                    int row = unknown[b.indices[k]];
                    if (row < 0) continue;
                    double weight = b.weights[k];
                    for (int axis = 0; axis < 3; axis++) rhs[row][axis] += weight * target[axis];
                    for (int l = 0; l < 4; l++) {
                        int col = unknown[b.indices[l]];
                        if (col >= 0) normal[row][col] += weight * b.weights[l];
                    }
                }
            }
            solve(normal, rhs);
            for (int id = 0; id < count; id++)
                if (unknown[id] >= 0) {
                    var xyz = rhs[unknown[id]];
                    values[id] =
                            new SurfaceProjection.Vertex(xyz[0] + origin.x(), xyz[1] + origin.y(), xyz[2] + origin.z());
                }
        }
        for (var p : values)
            if (p == null || !finite(p)) throw new IllegalArgumentException("unidentifiable/nonfinite source control");
        var controls = List.of(values);
        var fitted = new Fit(cells, controls, 0);
        double residual = 0;
        for (var sample : unique.values()) {
            var actual = fitted.evaluate(sample.u(), sample.v());
            var p = sample.position();
            residual = Math.max(
                    residual, Math.hypot(Math.hypot(actual.x() - p.x(), actual.y() - p.y()), actual.z() - p.z()));
        }
        if (!Double.isFinite(residual)) throw new IllegalArgumentException("source residual overflow");
        return new Fit(cells, controls, residual);
    }
    /** Promote a coarser source-aligned bilinear grid exactly to the requested grid.
     * This preserves its complete function, including shared sampled boundaries. */
    static Fit fitPromoted(List<Sample> source, int targetCells) {
        Objects.requireNonNull(source, "source");
        if (targetCells < 1 || targetCells > MAX_CELLS || source.isEmpty() || source.size() > MAX_SAMPLES)
            throw new IllegalArgumentException("bounded promoted fit required");
        var parameters = new java.util.HashSet<Parameter>();
        for (var sample : source) {
            Objects.requireNonNull(sample, "sample");
            parameters.add(new Parameter(sample.u(), sample.v()));
        }
        for (int cells = targetCells; cells >= 1; cells--) {
            if (targetCells % cells != 0) continue;
            boolean aligned = true;
            for (int i = 0; i <= cells; i++)
                for (var p : List.of(
                        new Parameter((double) i / cells, 0),
                        new Parameter((double) i / cells, 1),
                        new Parameter(0, (double) i / cells),
                        new Parameter(1, (double) i / cells))) aligned &= parameters.contains(p);
            if (!aligned) continue;
            var fit = fit(source, cells);
            if (cells == targetCells) return fit;
            var promoted = new java.util.ArrayList<SurfaceProjection.Vertex>();
            for (int y = 0; y <= targetCells; y++)
                for (int x = 0; x <= targetCells; x++)
                    promoted.add(fit.evaluate((double) x / targetCells, (double) y / targetCells));
            return new Fit(targetCells, promoted, fit.maximumSourceResidual());
        }
        throw new IllegalArgumentException("source chart has no complete sampled boundary");
    }

    private static Basis basis(double u, double v, int cells) {
        if (!Double.isFinite(u) || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
            throw new IllegalArgumentException("chart evaluation outside domain");
        int x = Math.min((int) Math.floor(u * cells), cells - 1),
                y = Math.min((int) Math.floor(v * cells), cells - 1),
                id = y * (cells + 1) + x;
        double a = u * cells - x, b = v * cells - y;
        return new Basis(
                new int[] {id, id + 1, id + cells + 1, id + cells + 2},
                new double[] {(1 - a) * (1 - b), a * (1 - b), (1 - a) * b, a * b});
    }

    private static void solve(double[][] matrix, double[][] rhs) {
        int n = matrix.length;
        double scale = 0;
        for (int i = 0; i < n; i++) scale = Math.max(scale, matrix[i][i]);
        if (!Double.isFinite(scale) || scale <= 0)
            throw new IllegalArgumentException("source chart has no interior constraints");
        // In-place Cholesky; no ridge term or invented surface when rank is insufficient.
        for (int i = 0; i < n; i++)
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i][j];
                for (int k = 0; k < j; k++) sum -= matrix[i][k] * matrix[j][k];
                if (i == j) {
                    if (!Double.isFinite(sum) || sum <= scale * 1e-14)
                        throw new IllegalArgumentException("source chart constraints are rank deficient");
                    matrix[i][j] = Math.sqrt(sum);
                } else matrix[i][j] = sum / matrix[j][j];
            }
        for (int i = 0; i < n; i++)
            for (int axis = 0; axis < 3; axis++) {
                double value = rhs[i][axis];
                for (int j = 0; j < i; j++) value -= matrix[i][j] * rhs[j][axis];
                rhs[i][axis] = value / matrix[i][i];
            }
        for (int i = n - 1; i >= 0; i--)
            for (int axis = 0; axis < 3; axis++) {
                double value = rhs[i][axis];
                for (int j = i + 1; j < n; j++) value -= matrix[j][i] * rhs[j][axis];
                rhs[i][axis] = value / matrix[i][i];
            }
    }

    private static boolean finite(SurfaceProjection.Vertex p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }
}
