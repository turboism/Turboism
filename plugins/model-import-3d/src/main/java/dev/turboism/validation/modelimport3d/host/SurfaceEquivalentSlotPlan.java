package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Explicit lossless candidate: removes identical attribute slots, never faces.
 * The ordinary importer retains its source slots unless this plan is requested. */
final class SurfaceEquivalentSlotPlan {
    private SurfaceEquivalentSlotPlan() {}

    private record Key(
            int surface,
            int chart,
            SurfaceProjection.Vertex position,
            Point2 uv,
            Point2 local,
            SurfaceProjection.Vertex normal) {}

    record Result(
            SurfaceWarpChartPlan.Plan plan, List<List<Integer>> oldToNew, long originalSlots, long optimizedSlots) {
        Result {
            Objects.requireNonNull(plan);
            oldToNew = oldToNew.stream().map(List::copyOf).toList();
        }

        long removedSlots() {
            return originalSlots - optimizedSlots;
        }
    }

    static Result compile(SurfaceWarpChartPlan.Plan input) {
        Objects.requireNonNull(input);
        var meshes = new ArrayList<SurfaceWarpChartPlan.Mesh>();
        var mappings = new ArrayList<List<Integer>>();
        long original = 0, optimized = 0;
        for (var mesh : input.meshes()) {
            int n = mesh.sourcePositions().size();
            if (mesh.sourceUvs().size() != n
                    || mesh.warpLocal().size() != n
                    || mesh.slotIdentities().size() != n
                    || mesh.indices().size() != mesh.triangles().size() * 3)
                throw new IllegalArgumentException("complete chart attribute/face domains required");
            var keys = new LinkedHashMap<Key, Integer>();
            var mapping = new ArrayList<Integer>();
            var positions = new ArrayList<SurfaceProjection.Vertex>();
            var uvs = new ArrayList<Point2>();
            var local = new ArrayList<Point2>();
            var identities = new ArrayList<SurfaceWarpChartPlan.Slot>();
            for (int i = 0; i < n; i++) {
                var slot = mesh.slotIdentities().get(i);
                var source = input.sources().get(slot.surface());
                if (!mesh.sourcePositions().get(i).equals(source.positions().get(slot.sourceSlot()))
                        || !mesh.sourceUvs().get(i).equals(source.textureUvs().get(slot.sourceSlot())))
                    throw new IllegalArgumentException("exact original source attributes required");
                var normal = source.sourceNormals().isEmpty()
                        ? null
                        : source.sourceNormals().get(slot.sourceSlot());
                var key = new Key(
                        slot.surface(),
                        slot.chart(),
                        mesh.sourcePositions().get(i),
                        mesh.sourceUvs().get(i),
                        mesh.warpLocal().get(i),
                        normal);
                Integer index = keys.get(key);
                if (index == null) {
                    index = positions.size();
                    keys.put(key, index);
                    positions.add(key.position());
                    uvs.add(key.uv());
                    local.add(key.local());
                    identities.add(slot);
                }
                mapping.add(index);
            }
            var indices = new ArrayList<Integer>();
            for (int i : mesh.indices()) {
                if (i < 0 || i >= n) throw new IllegalArgumentException("original index outside slot domain");
                indices.add(mapping.get(i));
            }
            // Every face, winding, painter position, and corner attribute is retained.
            meshes.add(new SurfaceWarpChartPlan.Mesh(positions, uvs, local, identities, indices, mesh.triangles()));
            mappings.add(mapping);
            original += n;
            optimized += positions.size();
        }
        return new Result(
                new SurfaceWarpChartPlan.Plan(
                        input.sources(),
                        meshes,
                        input.columns(),
                        input.rows(),
                        input.sourceControls(),
                        input.maximumSourceResidual(),
                        input.controlSamples()),
                mappings,
                original,
                optimized);
    }
}
