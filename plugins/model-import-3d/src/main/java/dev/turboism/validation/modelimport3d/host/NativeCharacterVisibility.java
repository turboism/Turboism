package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import java.util.*;

/** Sampled screen-overlap ordering. Cyclic/intersecting surfaces remain an approximation.
 * No pixels replace source geometry; the depth samples only select native draw order.
 */
final class NativeCharacterVisibility {
    private NativeCharacterVisibility() {}

    static final int MAX_PARTITIONS = 1001;
    static final int MAX_DEPTH_SLOTS = 40_000_000;
    static final int MAX_CONSTRAINTS = 250_000;
    static final long MAX_CONSTRAINT_PROJECTIONS = 20_000_000;

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Native visibility cancelled");
    }

    static List<List<Integer>> compile(
            List<NativeCharacterPlan.Partition> partitions, SurfaceProjection.Camera camera, ViewerScene scene) {
        int n = partitions.size();
        if (n > MAX_PARTITIONS)
            throw new IllegalArgumentException("Native draw order exceeds 1001 distinct ranks: " + n);
        if (n <= 1)
            return java.util.stream.IntStream.range(0, 25)
                    .mapToObj(i -> n == 0 ? List.<Integer>of() : List.of(500))
                    .toList();
        var first = partitions.get(0);
        boolean coincident = partitions.stream()
                .allMatch(p -> p.positions().equals(first.positions())
                        && p.indices().equals(first.indices())
                        && p.doubleSided() == first.doubleSided());
        if (coincident) {
            // Identical surfaces have no strict depth inequality in any default pose.
            // Keep source creation precedence instead of rasterizing them thousands of times.
            var ranks = java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> (int) Math.round(1000.0 * i / (n - 1)))
                    .toList();
            return java.util.stream.IntStream.range(0, 25).mapToObj(i -> ranks).toList();
        }
        int s = Math.min(384, (int) Math.sqrt(MAX_DEPTH_SLOTS / (double) Math.max(1, n)));
        float[][] z = new float[n][s * s];
        var result = new ArrayList<List<Integer>>();
        for (float yaw : NativeCharacterPlan.KEYS)
            for (float pitch : NativeCharacterPlan.KEYS) {
                var pose = new SurfaceProjection.Pose(yaw, pitch);
                var sorted = order(partitions, camera, scene, pose, s, z);
                var ranks = new ArrayList<>(Collections.nCopies(n, 0));
                for (int rank = 0; rank < n; rank++)
                    ranks.set(sorted.get(rank), n == 1 ? 500 : (int) Math.round(1000.0 * rank / (n - 1)));
                result.add(List.copyOf(ranks));
            }
        return constrain(partitions, camera, scene, s, z, result);
    }

    static final class RefinementRequired extends IllegalArgumentException {
        final int sourceMesh;
        final List<Integer> triangles;

        RefinementRequired(NativeCharacterPlan.Partition partition, String reason) {
            super(reason + "; sourceMesh=" + partition.sourceMeshIndex() + " triangles=" + partition.triangleCount());
            sourceMesh = partition.sourceMeshIndex();
            triangles = partition.sourceTriangles().stream().sorted().toList();
        }
    }

    private static IllegalArgumentException conflict(
            List<NativeCharacterPlan.Partition> partitions, int a, int b, String reason) {
        var first = partitions.get(a);
        var second = partitions.get(b);
        var split = first.triangleCount() >= second.triangleCount() ? first : second;
        return split.triangleCount() > 1
                ? new RefinementRequired(split, reason)
                : new IllegalArgumentException(reason + " between indivisible source triangles");
    }

    record Constraint(int front, int back, int[] keys, double[] weights) {}

    static List<List<Integer>> constrain(
            List<NativeCharacterPlan.Partition> partitions,
            SurfaceProjection.Camera camera,
            ViewerScene scene,
            int s,
            float[][] z,
            List<List<Integer>> initial) {
        int n = partitions.size(), k = NativeCharacterPlan.KEYS.size();
        if (n < 2) return List.copyOf(initial);
        var rows = new ArrayList<Constraint>();
        int[][] previousBounds = new int[n][];
        var poses = new ArrayList<SurfaceProjection.Pose>();
        for (int y = -180; y <= 180; y += 15)
            for (int p = -180; p <= 180; p += 15) poses.add(new SurfaceProjection.Pose(y, p));
        for (int y = -8; y <= 8; y++)
            for (int p = -8; p <= 8; p++)
                if (y % 4 != 0 || p % 4 != 0) poses.add(new SurfaceProjection.Pose(y * 3.75, p * 3.75));
        for (var pose : poses) {
            checkCancelled();
            double yaw = pose.yaw(), pitch = pose.pitch();
            int y0 = Math.min(k - 2, (int) Math.floor((yaw + 180) / (360.0 / (k - 1))));
            int p0 = Math.min(k - 2, (int) Math.floor((pitch + 180) / (360.0 / (k - 1))));
            double fy = (yaw - NativeCharacterPlan.KEYS.get(y0))
                    / (NativeCharacterPlan.KEYS.get(y0 + 1) - NativeCharacterPlan.KEYS.get(y0));
            double fp = (pitch - NativeCharacterPlan.KEYS.get(p0))
                    / (NativeCharacterPlan.KEYS.get(p0 + 1) - NativeCharacterPlan.KEYS.get(p0));
            var votes = rasterVotes(
                    partitions, camera, scene, new SurfaceProjection.Pose(yaw, pitch), s, z, previousBounds);
            int[] knots = {y0 * k + p0, y0 * k + p0 + 1, (y0 + 1) * k + p0, (y0 + 1) * k + p0 + 1};
            double[] weights = {(1 - fy) * (1 - fp), (1 - fy) * fp, fy * (1 - fp), fy * fp};
            for (int back = 0; back < n; back++)
                for (int front = 0; front < n; front++)
                    if (votes[back][front] > 0) {
                        if (votes[front][back] > 0)
                            throw conflict(
                                    partitions, front, back, "Cyclic screen overlap at yaw=" + yaw + " pitch=" + pitch);
                        if (rows.size() >= MAX_CONSTRAINTS)
                            throw new IllegalArgumentException("Native overlap constraint budget exceeded");
                        rows.add(new Constraint(front, back, knots, weights));
                    }
        }
        double[] values = new double[k * k * n];
        for (int knot = 0; knot < k * k; knot++)
            for (int i = 0; i < n; i++) values[knot * n + i] = initial.get(knot).get(i);
        double maximum = 0;
        int pass = 0;
        long projections = 0;
        for (; pass < 256; pass++) {
            checkCancelled();
            maximum = 0;
            for (var row : rows) {
                if (++projections > MAX_CONSTRAINT_PROJECTIONS)
                    throw conflict(partitions, row.front, row.back, "Native overlap solver work budget exceeded");
                double difference = 0, norm = 0;
                for (int c = 0; c < 4; c++) {
                    difference +=
                            row.weights[c] * (values[row.keys[c] * n + row.front] - values[row.keys[c] * n + row.back]);
                    norm += 2 * row.weights[c] * row.weights[c];
                }
                double violation = 8 - difference;
                maximum = Math.max(maximum, violation);
                if (violation <= .001) continue;
                for (int c = 0; c < 4; c++) {
                    int a = row.keys[c] * n + row.front, b = row.keys[c] * n + row.back;
                    double delta = violation * row.weights[c] / norm;
                    values[a] = Math.min(1000, Math.max(0, values[a] + delta));
                    values[b] = Math.min(1000, Math.max(0, values[b] - delta));
                }
            }
            if (maximum < .01) break;
        }
        if (maximum > .01) {
            Constraint worst = null;
            double violation = Double.NEGATIVE_INFINITY;
            for (var row : rows) {
                double difference = 0;
                for (int c = 0; c < 4; c++)
                    difference +=
                            row.weights[c] * (values[row.keys[c] * n + row.front] - values[row.keys[c] * n + row.back]);
                if (8 - difference > violation) {
                    violation = 8 - difference;
                    worst = row;
                }
            }
            throw conflict(partitions, worst.front, worst.back, "Unresolved sampled ordering constraints");
        }
        var result = new ArrayList<List<Integer>>();
        for (int knot = 0; knot < k * k; knot++) {
            var ranks = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) ranks.add((int) Math.round(values[knot * n + i]));
            result.add(List.copyOf(ranks));
        }
        // Rounding must not erase any sampled ordering constraint. Eight units leave
        // headroom for native staged truncation; actual native rendering is gated separately.
        for (var row : rows) {
            double difference = 0;
            for (int c = 0; c < 4; c++)
                difference += row.weights[c]
                        * (result.get(row.keys[c]).get(row.front)
                                - result.get(row.keys[c]).get(row.back));
            if (difference < 6.99) throw new IllegalArgumentException("Rounded native overlap constraint violated");
        }
        return List.copyOf(result);
    }

    static List<Integer> order(
            List<NativeCharacterPlan.Partition> partitions,
            SurfaceProjection.Camera camera,
            ViewerScene scene,
            SurfaceProjection.Pose pose,
            int s,
            float[][] z) {
        int n = partitions.size();
        long[][] votes = rasterVotes(partitions, camera, scene, pose, s, z);
        var centers = SurfaceProjection.project(
                        partitions.stream()
                                .map(NativeCharacterPlan.Partition::center)
                                .toList(),
                        camera,
                        pose)
                .depths();
        long[] outgoing = new long[n], incoming = new long[n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                outgoing[i] += votes[i][j];
                incoming[j] += votes[i][j];
            }
        boolean[] used = new boolean[n];
        var sorted = new ArrayList<Integer>();
        for (int step = 0; step < n; step++) {
            int best = -1;
            long score = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                long sc = incoming[i] == 0 ? Long.MAX_VALUE / 2 : outgoing[i] - incoming[i];
                if (best < 0 || sc > score || sc == score && centers.get(i) < centers.get(best)) {
                    best = i;
                    score = sc;
                }
            }
            used[best] = true;
            sorted.add(best);
            for (int i = 0; i < n; i++) {
                outgoing[i] -= votes[i][best];
                incoming[i] -= votes[best][i];
            }
        }
        // insertion descent minimizes violated coverage constraints
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (int a = 0; a < n; a++) {
                int id = sorted.get(a), best = a;
                long delta = 0, bestDelta = 0;
                for (int b = a + 1; b < n; b++) {
                    int j = sorted.get(b);
                    delta += votes[id][j] - votes[j][id];
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        best = b;
                    }
                }
                delta = 0;
                for (int b = a - 1; b >= 0; b--) {
                    int j = sorted.get(b);
                    delta += votes[j][id] - votes[id][j];
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        best = b;
                    }
                }
                if (best != a) {
                    sorted.remove(a);
                    sorted.add(best, id);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return sorted;
    }

    private static long[][] rasterVotes(
            List<NativeCharacterPlan.Partition> partitions,
            SurfaceProjection.Camera camera,
            ViewerScene scene,
            SurfaceProjection.Pose pose,
            int s,
            float[][] z) {
        return rasterVotes(partitions, camera, scene, pose, s, z, null);
    }

    static long[][] rasterVotes(
            List<NativeCharacterPlan.Partition> partitions,
            SurfaceProjection.Camera camera,
            ViewerScene scene,
            SurfaceProjection.Pose pose,
            int s,
            float[][] z,
            int[][] previousBounds) {
        int n = partitions.size();
        int[][] bounds = new int[n][4];
        for (var box : bounds) {
            box[0] = s;
            box[1] = s;
            box[2] = -1;
            box[3] = -1;
        }
        for (int i = 0; i < n; i++) {
            var previous = previousBounds == null ? null : previousBounds[i];
            if (previous == null) Arrays.fill(z[i], Float.NEGATIVE_INFINITY);
            else
                for (int y = previous[1]; y <= previous[3]; y++)
                    Arrays.fill(z[i], y * s + previous[0], y * s + previous[2] + 1, Float.NEGATIVE_INFINITY);
        }
        for (int i = 0; i < n; i++) {
            var p = partitions.get(i);
            if (!p.visible() || scene.meshes().get(p.sourceMeshIndex()).defaultOpacity() <= 0f) continue;
            var projected = SurfaceProjection.project(p.positions(), camera, pose);
            var xy = projected.positions();
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Native visibility cancelled");
            var mesh = scene.meshes().get(p.sourceMeshIndex());
            var material = p.materialIndex() >= 0
                            && p.materialIndex() < scene.materials().size()
                    ? scene.materials().get(p.materialIndex())
                    : null;
            var tex = material != null && material.baseColorTexture() >= 0
                    ? scene.textures().get(material.baseColorTexture())
                    : null;
            for (int t = 0; t < p.indices().size(); t += 3) {
                int a = p.indices().get(t),
                        b = p.indices().get(t + 1),
                        c = p.indices().get(t + 2);
                // Projection already includes framing/pivot compensation in canvas coordinates.
                // Subtracting camera.center again moves the sampling window when the source is translated.
                double x0 = xy.get(a).x() * s / 1024,
                        y0 = xy.get(a).y() * s / 1024,
                        x1 = xy.get(b).x() * s / 1024,
                        y1 = xy.get(b).y() * s / 1024,
                        x2 = xy.get(c).x() * s / 1024,
                        y2 = xy.get(c).y() * s / 1024;
                double d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
                if (Math.abs(d) < 1e-8 || !p.doubleSided() && d >= 0) continue;
                int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
                int maxX = Math.min(s - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
                int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
                int maxY = Math.min(s - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
                if (minX > maxX || minY > maxY) continue;
                var box = bounds[i];
                box[0] = Math.min(box[0], minX);
                box[1] = Math.min(box[1], minY);
                box[2] = Math.max(box[2], maxX);
                box[3] = Math.max(box[3], maxY);
                boolean opaque = material == null || "OPAQUE".equals(material.alphaMode());
                for (int y = minY; y <= maxY; y++)
                    for (int x = minX; x <= maxX; x++) {
                        double w0 = ((y1 - y2) * (x + .5 - x2) + (x2 - x1) * (y + .5 - y2)) / d,
                                w1 = ((y2 - y0) * (x + .5 - x2) + (x0 - x2) * (y + .5 - y2)) / d,
                                w2 = 1 - w0 - w1;
                        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                        if (!opaque) {
                            double
                                    u =
                                            w0 * p.uvs().get(a).x()
                                                    + w1 * p.uvs().get(b).x()
                                                    + w2 * p.uvs().get(c).x(),
                                    v =
                                            w0 * p.uvs().get(a).y()
                                                    + w1 * p.uvs().get(b).y()
                                                    + w2 * p.uvs().get(c).y();
                            if (!covered(p, mesh, material, tex, a, b, c, w0, w1, w2, u, v)) continue;
                        }
                        float dep = (float) (w0 * projected.depths().get(a)
                                + w1 * projected.depths().get(b)
                                + w2 * projected.depths().get(c));
                        int k = y * s + x;
                        if (dep > z[i][k]) z[i][k] = dep;
                    }
            }
        }
        if (previousBounds != null) for (int i = 0; i < n; i++) previousBounds[i] = bounds[i];
        return coverageVotes(z, s, bounds);
    }

    /** Same votes as the full scan; triangle bounds skip only guaranteed-empty pixels. */
    static long[][] coverageVotes(float[][] depth, int size, int[][] bounds) {
        int n = depth.length, pixels = size * size;
        int[] front = new int[pixels];
        float[] nearest = new float[pixels];
        Arrays.fill(front, -1);
        Arrays.fill(nearest, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < n; i++) {
            var b = bounds[i];
            var row = depth[i];
            for (int y = b[1]; y <= b[3]; y++)
                for (int k = y * size + b[0], end = y * size + b[2]; k <= end; k++)
                    if (row[k] > nearest[k]) {
                        nearest[k] = row[k];
                        front[k] = i;
                    }
        }
        long[][] votes = new long[n][n];
        for (int i = 0; i < n; i++) {
            var b = bounds[i];
            var row = depth[i];
            var outgoing = votes[i];
            for (int y = b[1]; y <= b[3]; y++)
                for (int k = y * size + b[0], end = y * size + b[2]; k <= end; k++) {
                    int best = front[k];
                    if (best >= 0 && i != best && Float.isFinite(row[k]) && row[k] < nearest[k] - 1e-6)
                        outgoing[best]++;
                }
        }
        return votes;
    }

    /** Row-contiguous passes preserve pixel voting and first-partition tie precedence. */
    static long[][] coverageVotes(float[][] depth, int pixels) {
        int n = depth.length;
        int[] front = new int[pixels];
        float[] nearest = new float[pixels];
        Arrays.fill(front, -1);
        Arrays.fill(nearest, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < n; i++) {
            float[] row = depth[i];
            for (int k = 0; k < pixels; k++) {
                if (row[k] > nearest[k]) {
                    nearest[k] = row[k];
                    front[k] = i;
                }
            }
        }
        long[][] votes = new long[n][n];
        for (int i = 0; i < n; i++) {
            float[] row = depth[i];
            long[] outgoing = votes[i];
            for (int k = 0; k < pixels; k++) {
                int best = front[k];
                if (best >= 0 && i != best && Float.isFinite(row[k]) && row[k] < nearest[k] - 1e-6) outgoing[best]++;
            }
        }
        return votes;
    }

    private static boolean covered(
            NativeCharacterPlan.Partition p,
            ViewerScene.Mesh mesh,
            ViewerScene.Material material,
            ViewerScene.Texture tex,
            int a,
            int b,
            int c,
            double w0,
            double w1,
            double w2,
            double u,
            double v) {
        if (material == null || "OPAQUE".equals(material.alphaMode())) return true;
        double alpha = material.baseColorFactor()[3];
        if (tex != null) {
            int x = Math.min(tex.width() - 1, (int) ((u - Math.floor(u)) * tex.width()));
            int y = Math.min(tex.height() - 1, (int) ((v - Math.floor(v)) * tex.height()));
            alpha *= (tex.argb()[y * tex.width() + x] >>> 24) / 255.0;
        }
        if (mesh.colors() != null) {
            var colors = mesh.colors();
            alpha *= w0 * colors[p.sourceSlotMapping().get(a) * 4 + 3]
                    + w1 * colors[p.sourceSlotMapping().get(b) * 4 + 3]
                    + w2 * colors[p.sourceSlotMapping().get(c) * 4 + 3];
        }
        return alpha > 0 && alpha >= ("MASK".equals(material.alphaMode()) ? material.alphaCutoff() : 0.5);
    }
}
