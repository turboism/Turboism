package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Derived editable plane, separate from original UV/material provenance and Warp-local
 * positions. No original face, corner, or source attribute is removed by this plan. */
final class SurfaceDerivedPlanePlan {
    private SurfaceDerivedPlanePlan() { }
    // Official SCALE_1 cache aligns dimensions to 64 texels. Raw pixels stay
    // unchanged; normalized UVs use the same rounded reciprocal as its affine.
    static final int IMAGE_SIZE = 576;
    static final int CONTENT_SIZE = 520;
    static final int GUTTER = 4;
    static final int LATTICE_CELLS = 256;

    record Plan(SurfaceWarpChartPlan.Mesh original, ArtMeshGeometry geometry,
            List<Point2> defaultLocal, List<Integer> oldToNew, List<Integer> representatives) {
        Plan {
            Objects.requireNonNull(original); Objects.requireNonNull(geometry);
            defaultLocal = List.copyOf(defaultLocal); oldToNew = List.copyOf(oldToNew);
            representatives = List.copyOf(representatives);
        }
    }
    private record Attribute(SurfaceProjection.Vertex position, Point2 local) { }

    static Plan compile(SurfaceWarpChartPlan.Mesh mesh) {
        Objects.requireNonNull(mesh);
        int count = mesh.sourcePositions().size();
        if (count < 3 || count > 65535 || mesh.warpLocal().size() != count
                || mesh.sourceUvs().size() != count || mesh.slotIdentities().size() != count
                || mesh.indices().isEmpty() || mesh.indices().size() % 3 != 0
                || mesh.indices().size() > 600000
                || mesh.triangles().size() != mesh.indices().size() / 3)
            throw new IllegalArgumentException("bounded complete original chart required");
        int chart = mesh.slotIdentities().get(0).chart();
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < count; i++) {
            if (mesh.slotIdentities().get(i).chart() != chart)
                throw new IllegalArgumentException("derived source plane requires one chart per mesh");
            var p = mesh.warpLocal().get(i);
            if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()))
                throw new IllegalArgumentException("finite original Warp-local domain required");
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y());
        }
        if (!(maxX > minX && maxY > minY))
            throw new IllegalArgumentException("nonzero chart rectangle required");
        var attributes = new LinkedHashMap<Attribute, Integer>();
        var occupied = new HashMap<Point2, Attribute>();
        var positions = new ArrayList<Point2>();
        var uvs = new ArrayList<Point2>();
        var local = new ArrayList<Point2>();
        var representatives = new ArrayList<Integer>();
        var remap = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) {
            var originalLocal = mesh.warpLocal().get(i);
            var key = new Attribute(mesh.sourcePositions().get(i), originalLocal);
            float x = GUTTER + 2 * Math.round((originalLocal.x() - minX) / (maxX - minX) * LATTICE_CELLS);
            float y = GUTTER + 2 * Math.round((originalLocal.y() - minY) / (maxY - minY) * LATTICE_CELLS);
            var point = new Point2(x, y);
            var previous = occupied.putIfAbsent(point, key);
            if (previous != null && !previous.equals(key))
                throw new IllegalArgumentException("integer source plane overlaps distinct original positions; finer charting required");
            Integer slot = attributes.get(key);
            if (slot == null) {
                slot = attributes.size(); attributes.put(key, slot);
                positions.add(point); uvs.add(new Point2(x * (1f / IMAGE_SIZE), y * (1f / IMAGE_SIZE)));
                local.add(originalLocal); representatives.add(i);
            }
            remap.add(slot);
        }
        var indices = new ArrayList<Integer>(mesh.indices().size());
        for (int index : mesh.indices()) {
            if (index < 0 || index >= count)
                throw new IllegalArgumentException("original corner outside slot domain");
            indices.add(remap.get(index));
        }
        // Degenerate/duplicate/reversed faces remain explicit. The native preview and
        // texture mapper decide admission; this planner never drops an awkward face.
        return new Plan(mesh, new ArtMeshGeometry(positions, uvs, indices), local, remap, representatives);
    }
}
