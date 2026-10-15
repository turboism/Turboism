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

    static List<List<Integer>> compile(
            List<NativeCharacterPlan.Partition> partitions, SurfaceProjection.Camera camera, ViewerScene scene) {
        int n = partitions.size();
        if (n > MAX_PARTITIONS)
            throw new IllegalArgumentException("Native draw order exceeds 1001 distinct ranks: " + n);
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
        for (var a : z) Arrays.fill(a, Float.NEGATIVE_INFINITY);
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
                double x0 = (xy.get(a).x() - camera.centerX() + 512) * s / 1024,
                        y0 = (xy.get(a).y() - camera.centerY() + 512) * s / 1024,
                        x1 = (xy.get(b).x() - camera.centerX() + 512) * s / 1024,
                        y1 = (xy.get(b).y() - camera.centerY() + 512) * s / 1024,
                        x2 = (xy.get(c).x() - camera.centerX() + 512) * s / 1024,
                        y2 = (xy.get(c).y() - camera.centerY() + 512) * s / 1024;
                double d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
                if (Math.abs(d) < 1e-8 || !p.doubleSided() && d >= 0) continue;
                for (int y = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
                        y <= Math.min(s - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
                        y++)
                    for (int x = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
                            x <= Math.min(s - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
                            x++) {
                        double w0 = ((y1 - y2) * (x + .5 - x2) + (x2 - x1) * (y + .5 - y2)) / d,
                                w1 = ((y2 - y0) * (x + .5 - x2) + (x0 - x2) * (y + .5 - y2)) / d,
                                w2 = 1 - w0 - w1;
                        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
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
                        float dep = (float) (w0 * projected.depths().get(a)
                                + w1 * projected.depths().get(b)
                                + w2 * projected.depths().get(c));
                        int k = y * s + x;
                        if (dep > z[i][k]) z[i][k] = dep;
                    }
            }
        }
        long[][] votes = new long[n][n];
        for (int k = 0; k < s * s; k++) {
            int best = -1;
            float dep = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++)
                if (z[i][k] > dep) {
                    dep = z[i][k];
                    best = i;
                }
            if (best < 0) continue;
            for (int i = 0; i < n; i++)
                if (i != best && Float.isFinite(z[i][k]) && z[i][k] < dep - 1e-6) votes[i][best]++;
        }
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
