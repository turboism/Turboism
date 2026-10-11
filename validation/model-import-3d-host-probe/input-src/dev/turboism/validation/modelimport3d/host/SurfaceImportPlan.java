package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/** Pure, bounded projection plan. Visibility and native creation remain separate gates. */
final class SurfaceImportPlan {
    private SurfaceImportPlan() { }
    static final long MAX_PROJECTED_VERTICES=20_000_000;
    static final long MAX_KEYFORMS=131_072;
    record Allocation(long projectedVertices,long keyforms,long packedCoordinateBytes) { }

    record Key(float yaw, float pitch) { }
    record Patch(int surfaceIndex, SurfacePatchPlan.Patch source, List<Integer> neutralCollapsedTriangles,
            List<List<Point2>> positionsByKey) {
        Patch {
            neutralCollapsedTriangles = List.copyOf(neutralCollapsedTriangles);
            positionsByKey = positionsByKey.stream().map(List::copyOf).toList();
        }
    }
    record Plan(SurfaceProjection.Camera camera, SurfaceKeyPlan.Grid grid, List<Key> keys,
            int neutralKey, List<Patch> patches, long projectedVertices) {
        Plan { keys = List.copyOf(keys); patches = List.copyOf(patches); }
        Allocation allocation() {
            return new Allocation(projectedVertices,Math.multiplyExact((long)patches.size(),keys.size()),Math.multiplyExact(projectedVertices,8));
        }
    }

    static Plan compile(List<GlbSurfaceReader.Surface> surfaces, SurfaceProjection.Camera camera,
            double yawLimit, double pitchLimit, double idealTolerancePixels, long maxProjectedVertices) {
        if (surfaces.isEmpty() || maxProjectedVertices < 1 || maxProjectedVertices > MAX_PROJECTED_VERTICES) {
            throw new IllegalArgumentException("invalid import projection budget/input");
        }
        var vertices = surfaces.stream().flatMap(s -> s.positions().stream()).toList();
        var grid = SurfaceKeyPlan.linear(vertices, camera, yawLimit, pitchLimit, idealTolerancePixels, 4096);
        var yawKeys = SurfaceKeyformBake.bindingKeys(grid.yawKeys());
        var pitchKeys = SurfaceKeyformBake.bindingKeys(grid.pitchKeys());
        var keys = new ArrayList<Key>();
        int neutral = -1;
        for (float yaw : yawKeys) for (float pitch : pitchKeys) {
            if (yaw == 0 && pitch == 0) neutral = keys.size();
            keys.add(new Key(yaw, pitch));
        }
        // Count expanded patch vertices (creases can duplicate a source slot) before projection allocation.
        var sources = surfaces.stream().map(SurfacePatchPlan::plan).toList();
        long patchCount = sources.stream().mapToLong(List::size).sum();
        long vertexCount = sources.stream().flatMap(List::stream).mapToLong(p -> p.positions().size()).sum();
        long projected = Math.multiplyExact(vertexCount, keys.size());
        if (projected > maxProjectedVertices || Math.multiplyExact(patchCount,keys.size()) > MAX_KEYFORMS) {
            throw new IllegalArgumentException("total import keyform/vertex budget exceeded: vertices="+projected
                +", keyforms="+Math.multiplyExact(patchCount,keys.size())+", minimum packed coordinate bytes="+Math.multiplyExact(projected,8));
        }
        var patches = new ArrayList<Patch>();
        for (int surface = 0; surface < sources.size(); surface++) {
            for (var patch : sources.get(surface)) {
                var positions = new ArrayList<List<Point2>>();
                for (var key : keys) {
                    positions.add(SurfaceProjection.project(patch.positions(), camera,
                        new SurfaceProjection.Pose(key.yaw(), key.pitch())).positions());
                }
                var collapsed = new ArrayList<Integer>();
                var zero = positions.get(neutral);
                for (int t = 0; t < patch.indices().size() / 3; t++) {
                    var a = zero.get(patch.indices().get(t * 3));
                    var b = zero.get(patch.indices().get(t * 3 + 1));
                    var c = zero.get(patch.indices().get(t * 3 + 2));
                    double area = ((double)b.x() - a.x()) * ((double)c.y() - a.y())
                        - ((double)b.y() - a.y()) * ((double)c.x() - a.x());
                    if (area == 0) collapsed.add(t);
                }
                // Edge-on faces must survive: they can become visible at non-neutral keys.
                patches.add(new Patch(surface, patch, collapsed, positions));
            }
        }
        return new Plan(camera, grid, keys, neutral, patches, projected);
    }
}
