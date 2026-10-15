package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;
import java.util.*;

/** Fits explicit source displacements to native bilinear Warp controls. Residuals are measured, not assumed. */
final class NativeControlWarpPlan {
    static final double MAX_RESIDUAL_PIXELS = 2.0;
    static final long MAX_CONTROL_SAMPLES = 1_000_000;

    record Key(String parameter, float value, List<Point2> controls, double residualPixels) {
        Key {
            controls = List.copyOf(controls);
        }
    }

    record Patch(int partition, int cells, Point2 origin, Point2 span, List<Key> keys) {
        Patch {
            keys = List.copyOf(keys);
        }
    }

    record Plan(
            List<ViewerScene.Control> controls,
            List<Patch> patches,
            long controlSamples,
            double maximumResidualPixels) {
        Plan {
            controls = List.copyOf(controls);
            patches = List.copyOf(patches);
        }
    }

    static final class FitFailure extends IllegalArgumentException {
        final int sourceMesh;
        final List<Integer> triangles;

        FitFailure(int sourceMesh, List<Integer> triangles, String message) {
            super(message);
            this.sourceMesh = sourceMesh;
            this.triangles = triangles;
        }
    }

    private record Basis(int[] ids, double[] weights) {}

    record CacheKey(int sourceMesh, List<Integer> triangles) {
        CacheKey {
            triangles = List.copyOf(triangles);
        }
    }
    /** One import only: scene, camera and anatomical frames must remain unchanged. */
    static final class FitCache {
        private final Map<CacheKey, Patch> patches = new HashMap<>();
    }

    static Plan prepare(ViewerScene scene, NativeCharacterPlan.Plan geometry) {
        return prepare(scene, geometry, new FitCache());
    }

    static Plan prepare(ViewerScene scene, NativeCharacterPlan.Plan geometry, FitCache cache) {
        if (scene.controls().isEmpty()) return new Plan(List.of(), List.of(), 0, 0);
        var frames = new HashMap<String, NativeCharacterAuthoring.Frame>();
        var camera = geometry.camera();
        float ox = geometry.request().roll().isPresent()
                ? (float) (camera.centerX() + camera.scale() * camera.pivot().x())
                : 0;
        float oy = geometry.request().roll().isPresent()
                ? (float) (camera.centerY() - camera.scale() * camera.pivot().y())
                : 0;
        for (var p : geometry.partitions()) {
            var mesh = scene.meshes().get(p.sourceMeshIndex());
            String group = mesh.bodyPart().isBlank() ? mesh.appearanceGroup() : mesh.bodyPart();
            var frame = frames.computeIfAbsent(group, ignored -> new NativeCharacterAuthoring.Frame());
            var projected = SurfaceProjection.project(p.positions(), camera, new SurfaceProjection.Pose(0, 0));
            for (var v : projected.positions()) frame.include(v.x() - ox, v.y() - oy);
        }
        frames.values().forEach(NativeCharacterAuthoring.Frame::pad);
        var patches = new ArrayList<Patch>();
        long count = 0;
        double maximum = 0;
        for (int pi = 0; pi < geometry.partitions().size(); pi++) {
            var p = geometry.partitions().get(pi);
            var mesh = scene.meshes().get(p.sourceMeshIndex());
            if (!mesh.visible() || mesh.controlSamples().isEmpty()) continue;
            String group = mesh.bodyPart().isBlank() ? mesh.appearanceGroup() : mesh.bodyPart();
            var frame = frames.get(group);
            var cacheKey = new CacheKey(p.sourceMeshIndex(), p.sourceTriangles());
            var cached = p.sourceTriangles().isEmpty() ? null : cache.patches.get(cacheKey);
            if (cached != null) {
                for (var key : cached.keys()) maximum = Math.max(maximum, key.residualPixels());
                count += (long) (cached.cells() + 1)
                        * (cached.cells() + 1)
                        * (cached.keys().size() + 1);
                checkBudget(count, geometry);
                patches.add(new Patch(pi, cached.cells(), cached.origin(), cached.span(), cached.keys()));
                continue;
            }
            var neutral = SurfaceProjection.project(p.positions(), camera, new SurfaceProjection.Pose(0, 0))
                    .positions();
            var anatomicalInputs = neutral.stream()
                    .map(v -> new Point2(
                            (v.x() - ox - frame.minX) / (frame.maxX - frame.minX),
                            (v.y() - oy - frame.minY) / (frame.maxY - frame.minY)))
                    .toList();
            double minX = anatomicalInputs.stream().mapToDouble(Point2::x).min().orElseThrow();
            double maxX = anatomicalInputs.stream().mapToDouble(Point2::x).max().orElseThrow();
            double minY = anatomicalInputs.stream().mapToDouble(Point2::y).min().orElseThrow();
            double maxY = anatomicalInputs.stream().mapToDouble(Point2::y).max().orElseThrow();
            // Cover this patch, rather than spending most grid controls on its entire anatomical part.
            double padX = 1.0 / (frame.maxX - frame.minX), padY = 1.0 / (frame.maxY - frame.minY);
            var origin = new Point2((float) (minX - padX), (float) (minY - padY));
            var span = new Point2((float) (maxX - minX + 2 * padX), (float) (maxY - minY + 2 * padY));
            var inputs = anatomicalInputs.stream()
                    .map(v -> new Point2((v.x() - origin.x()) / span.x(), (v.y() - origin.y()) / span.y()))
                    .toList();
            var active = new ArrayList<ViewerScene.ControlSample>();
            for (var sample : mesh.controlSamples()) {
                boolean moved = false;
                for (int slot : p.sourceSlotMapping()) {
                    if (sample.deltas().length != mesh.positions().length)
                        throw new IllegalArgumentException("source control slot count differs");
                    if (Math.abs(sample.deltas()[slot * 3])
                                    + Math.abs(sample.deltas()[slot * 3 + 1])
                                    + Math.abs(sample.deltas()[slot * 3 + 2])
                            > 1e-7) {
                        moved = true;
                        break;
                    }
                }
                if (moved) active.add(sample);
            }
            if (active.isEmpty()) continue;
            List<Key> keys = List.of();
            int cells;
            for (cells = 2; cells <= 32; cells *= 2) {
                var fits = new ArrayList<Key>();
                double worst = 0;
                for (var sample : active) {
                    double[] dx = new double[inputs.size()], dy = new double[inputs.size()];
                    for (int i = 0; i < inputs.size(); i++) {
                        int slot = p.sourceSlotMapping().get(i);
                        dx[i] = sample.deltas()[slot * 3] * camera.scale() / ((frame.maxX - frame.minX) * span.x());
                        dy[i] = -sample.deltas()[slot * 3 + 1]
                                * camera.scale()
                                / ((frame.maxY - frame.minY) * span.y());
                    }
                    var fit = fit(
                            inputs,
                            dx,
                            dy,
                            cells,
                            (frame.maxX - frame.minX) * span.x(),
                            (frame.maxY - frame.minY) * span.y());
                    fits.add(new Key(sample.controlId(), sample.key(), fit.points, fit.residual));
                    worst = Math.max(worst, fit.residual);
                    if (worst > MAX_RESIDUAL_PIXELS) break;
                }
                keys = List.copyOf(fits);
                if (worst <= MAX_RESIDUAL_PIXELS) break;
            }
            if (cells > 32)
                throw new FitFailure(
                        p.sourceMeshIndex(),
                        p.sourceTriangles(),
                        "Source controls cannot fit native Warp within 2px for partition " + pi + " source="
                                + mesh.name() + "; control="
                                + keys.stream()
                                        .max(java.util.Comparator.comparingDouble(Key::residualPixels))
                                        .orElseThrow()
                                        .parameter()
                                + "; residual="
                                + keys.stream()
                                        .mapToDouble(Key::residualPixels)
                                        .max()
                                        .orElse(0));
            for (var key : keys) maximum = Math.max(maximum, key.residualPixels());
            count += (long) (cells + 1) * (cells + 1) * (keys.size() + 1);
            checkBudget(count, geometry);
            var patch = new Patch(pi, cells, origin, span, keys);
            patches.add(patch);
            if (!p.sourceTriangles().isEmpty()) cache.patches.put(cacheKey, patch);
        }
        var used = new HashSet<String>();
        for (var patch : patches) for (var key : patch.keys()) used.add(key.parameter());
        for (var mesh : scene.meshes())
            if (mesh.visible()) for (var factor : mesh.opacityControls()) used.add(factor.controlId());
        return new Plan(
                scene.controls().stream().filter(c -> used.contains(c.id())).toList(), patches, count, maximum);
    }

    private static void checkBudget(long count, NativeCharacterPlan.Plan geometry) {
        if (count > MAX_CONTROL_SAMPLES
                || count + geometry.totalKeyformSlots() > NativeCharacterPlan.MAX_TOTAL_KEYFORM_SLOTS)
            throw new IllegalArgumentException("Native control form budget exceeded");
    }

    private record Fit(List<Point2> points, double residual) {}

    private static Fit fit(List<Point2> inputs, double[] dx, double[] dy, int cells, double sx, double sy) {
        Basis[] basis = inputs.stream().map(p -> basis(p.x(), p.y(), cells)).toArray(Basis[]::new);
        int count = (cells + 1) * (cells + 1);
        double[] x = solve(basis, dx, count), y = solve(basis, dy, count);
        var points = new ArrayList<Point2>();
        for (int j = 0; j <= cells; j++)
            for (int i = 0; i <= cells; i++) {
                int id = j * (cells + 1) + i;
                points.add(new Point2((float) ((double) i / cells + x[id]), (float) ((double) j / cells + y[id])));
            }
        double residual = 0;
        for (int i = 0; i < basis.length; i++) {
            double px = 0, py = 0;
            Basis b = basis[i];
            for (int k = 0; k < 4; k++) {
                px += x[b.ids[k]] * b.weights[k];
                py += y[b.ids[k]] * b.weights[k];
            }
            residual = Math.max(residual, Math.hypot((px - dx[i]) * sx, (py - dy[i]) * sy));
        }
        return new Fit(List.copyOf(points), residual);
    }

    static WarpGrid identity(int cells) {
        var points = new ArrayList<Point2>();
        for (int y = 0; y <= cells; y++)
            for (int x = 0; x <= cells; x++) points.add(new Point2((float) x / cells, (float) y / cells));
        return new WarpGrid(cells, cells, true, points);
    }

    private static Basis basis(double u, double v, int cells) {
        double x = Math.max(0, Math.min(cells, u * cells)), y = Math.max(0, Math.min(cells, v * cells));
        int ix = Math.min(cells - 1, (int) x), iy = Math.min(cells - 1, (int) y);
        x -= ix;
        y -= iy;
        int a = iy * (cells + 1) + ix;
        return new Basis(
                new int[] {a, a + 1, a + cells + 1, a + cells + 2},
                new double[] {(1 - x) * (1 - y), x * (1 - y), (1 - x) * y, x * y});
    }
    /** Small ridge term fixes unobserved controls at identity. Preconditioned conjugate gradients avoid dense matrices. */
    private static double[] solve(Basis[] basis, double[] values, int count) {
        double ridge = 1e-9, tol = 1e-18;
        double[] rhs = new double[count], diag = new double[count];
        Arrays.fill(diag, ridge);
        for (int i = 0; i < basis.length; i++) {
            var b = basis[i];
            for (int k = 0; k < 4; k++) {
                rhs[b.ids[k]] += b.weights[k] * values[i];
                diag[b.ids[k]] += b.weights[k] * b.weights[k];
            }
        }
        double[] x = new double[count], r = rhs.clone(), z = new double[count], p = new double[count];
        for (int i = 0; i < count; i++) z[i] = p[i] = r[i] / diag[i];
        double rz = dot(r, z), initial = dot(rhs, rhs);
        if (initial == 0) return x;
        for (int iter = 0; iter < Math.min(4000, count * 8); iter++) {
            double[] ap = new double[count];
            for (int i = 0; i < count; i++) ap[i] = ridge * p[i];
            for (var b : basis) {
                double v = 0;
                for (int k = 0; k < 4; k++) v += b.weights[k] * p[b.ids[k]];
                for (int k = 0; k < 4; k++) ap[b.ids[k]] += b.weights[k] * v;
            }
            double den = dot(p, ap);
            if (!(den > 0)) break;
            double alpha = rz / den;
            for (int i = 0; i < count; i++) {
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }
            if (dot(r, r) <= Math.max(tol, initial * 1e-14)) break;
            for (int i = 0; i < count; i++) z[i] = r[i] / diag[i];
            double next = dot(r, z), beta = next / rz;
            for (int i = 0; i < count; i++) p[i] = z[i] + beta * p[i];
            rz = next;
        }
        for (double value : x)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite native control fit");
        return x;
    }

    private static double dot(double[] a, double[] b) {
        double v = 0;
        for (int i = 0; i < a.length; i++) v += a[i] * b[i];
        return v;
    }

    private NativeControlWarpPlan() {}
}
