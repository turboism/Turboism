package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Bounded source-preserving chart atlas and mesh packing. Chart attribution and
 * triangle order are explicit inputs; neither constitutes native visibility admission. */
final class SurfaceWarpChartPlan {
    private SurfaceWarpChartPlan() {}

    record Parameter(double u, double v) {
        Parameter {
            if (!Double.isFinite(u) || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
                throw new IllegalArgumentException("chart corner outside unit square");
            if (u == 0) u = 0;
            if (v == 0) v = 0;
        }
    }

    record Triangle(int surface, int triangle) {}

    record ChartTriangle(Triangle source, int chart, List<Parameter> corners) {
        ChartTriangle {
            Objects.requireNonNull(source);
            corners = List.copyOf(corners);
            if (corners.size() != 3) throw new IllegalArgumentException("three source corners required");
        }
    }

    record Slot(int surface, int sourceSlot, int chart) {}

    record Mesh(
            List<SurfaceProjection.Vertex> sourcePositions,
            List<Point2> sourceUvs,
            List<Point2> warpLocal,
            List<Slot> slotIdentities,
            List<Integer> indices,
            List<Triangle> triangles) {
        Mesh {
            sourcePositions = List.copyOf(sourcePositions);
            sourceUvs = List.copyOf(sourceUvs);
            warpLocal = List.copyOf(warpLocal);
            slotIdentities = List.copyOf(slotIdentities);
            indices = List.copyOf(indices);
            triangles = List.copyOf(triangles);
        }
    }

    record Layout(
            int chartCount, int cells, int chartColumns, int keysPerAxis, long maxControlSamples, int maxMeshSlots) {
        Layout {
            if (chartCount < 1
                    || chartCount > 1024
                    || cells < 1
                    || cells > SurfaceWarpSpatialFit.MAX_CELLS
                    || chartColumns < 1
                    || chartColumns > chartCount
                    || keysPerAxis < 3
                    || keysPerAxis > 257
                    || keysPerAxis % 2 != 1
                    || maxControlSamples < 1
                    || maxControlSamples > SurfaceImportPlan.MAX_PROJECTED_VERTICES
                    || maxMeshSlots < 3
                    || maxMeshSlots > 65535) throw new IllegalArgumentException("invalid bounded Warp chart layout");
        }
    }

    record Plan(
            List<GlbSurfaceReader.Surface> sources,
            List<Mesh> meshes,
            int columns,
            int rows,
            List<SurfaceProjection.Vertex> sourceControls,
            double maximumSourceResidual,
            long controlSamples) {
        Plan {
            sources = List.copyOf(sources);
            meshes = List.copyOf(meshes);
            sourceControls = List.copyOf(sourceControls);
        }

        List<Point2> projectControls(SurfaceProjection.Camera camera, SurfaceProjection.Pose pose) {
            return SurfaceProjection.project(sourceControls, camera, pose).positions();
        }
    }

    static Plan compile(
            List<GlbSurfaceReader.Surface> sources,
            List<ChartTriangle> attribution,
            List<Triangle> order,
            Layout layout) {
        Objects.requireNonNull(layout);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(attribution);
        Objects.requireNonNull(order);
        if (sources.isEmpty() || sources.size() > 1024 || attribution.size() > 200000 || order.size() > 200000)
            throw new IllegalArgumentException("source primitive/triangle budget before input copying");
        sources = List.copyOf(sources);
        attribution = List.copyOf(attribution);
        order = List.copyOf(order);
        int columns = layout.chartColumns() * (layout.cells() + 1),
                chartRows = (layout.chartCount() + layout.chartColumns() - 1) / layout.chartColumns(),
                rows = chartRows * (layout.cells() + 1);
        long samples = (long) (columns + 1) * (rows + 1) * layout.keysPerAxis() * layout.keysPerAxis();
        if (columns > 512 || rows > 512 || samples > layout.maxControlSamples())
            throw new IllegalArgumentException("control grid/sample budget before fitting");
        int[] offsets = new int[sources.size() + 1];
        long sourceSlots = 0;
        for (int i = 0; i < sources.size(); i++) {
            var s = sources.get(i);
            sourceSlots += s.positions().size();
            if (s.positions().isEmpty()
                    || s.positions().size() != s.textureUvs().size()
                    || s.indices().isEmpty()
                    || s.indices().size() % 3 != 0)
                throw new IllegalArgumentException("source indexed position/UV domain");
            if ((long) offsets[i] + s.indices().size() / 3 > 200000 || sourceSlots > 100000)
                throw new IllegalArgumentException("source slot/index budget");
            offsets[i + 1] = offsets[i] + s.indices().size() / 3;
        }
        int triangleCount = offsets[sources.size()];
        if (attribution.size() != triangleCount || order.size() != triangleCount)
            throw new IllegalArgumentException("complete original triangle inventory required");
        var byTriangle = new ChartTriangle[triangleCount];
        var chartSamples = new ArrayList<HashMap<Parameter, SurfaceProjection.Vertex>>();
        for (int i = 0; i < layout.chartCount(); i++) chartSamples.add(new HashMap<>());
        var slotParameters = new HashMap<Slot, Parameter>();
        for (var attributed : attribution) {
            int id = index(attributed.source(), sources, offsets);
            if (byTriangle[id] != null || attributed.chart() < 0 || attributed.chart() >= layout.chartCount())
                throw new IllegalArgumentException("duplicate triangle/invalid chart");
            byTriangle[id] = attributed;
            var surface = sources.get(attributed.source().surface());
            for (int corner = 0; corner < 3; corner++) {
                int slot = surface.indices().get(attributed.source().triangle() * 3 + corner);
                if (slot < 0 || slot >= surface.positions().size())
                    throw new IllegalArgumentException("source index outside slot domain");
                var parameter = attributed.corners().get(corner);
                var identity = new Slot(attributed.source().surface(), slot, attributed.chart());
                var prior = slotParameters.putIfAbsent(identity, parameter);
                if (prior != null && !prior.equals(parameter))
                    throw new IllegalArgumentException("same source slot/chart has different coordinates");
                var position = surface.positions().get(slot);
                var existing = chartSamples.get(attributed.chart()).putIfAbsent(parameter, position);
                if (existing != null && !existing.equals(position))
                    throw new IllegalArgumentException("chart overlaps different source positions");
            }
        }
        var seen = new boolean[triangleCount];
        for (var triangle : order) {
            int id = index(triangle, sources, offsets);
            if (seen[id]) throw new IllegalArgumentException("triangle order is not a permutation");
            seen[id] = true;
        }
        // Reject invalid attribution/order and capacity before allocating the dense atlas or fitting.
        var fits = new ArrayList<SurfaceWarpSpatialFit.Fit>();
        double residual = 0;
        for (var chart : chartSamples) {
            var source = new ArrayList<SurfaceWarpSpatialFit.Sample>();
            for (var entry : chart.entrySet())
                source.add(new SurfaceWarpSpatialFit.Sample(
                        entry.getKey().u(), entry.getKey().v(), entry.getValue()));
            var fit = SurfaceWarpSpatialFit.fitPromoted(source, layout.cells());
            fits.add(fit);
            residual = Math.max(residual, fit.maximumSourceResidual());
        }
        var controls = new ArrayList<SurfaceProjection.Vertex>((columns + 1) * (rows + 1));
        for (int i = 0; i < (columns + 1) * (rows + 1); i++) controls.add(new SurfaceProjection.Vertex(0, 0, 0));
        for (int chart = 0; chart < fits.size(); chart++)
            for (int y = 0; y <= layout.cells(); y++)
                for (int x = 0; x <= layout.cells(); x++) {
                    int baseX = (chart % layout.chartColumns()) * (layout.cells() + 1),
                            baseY = (chart / layout.chartColumns()) * (layout.cells() + 1);
                    controls.set(
                            (baseY + y) * (columns + 1) + baseX + x,
                            fits.get(chart).controls().get(y * (layout.cells() + 1) + x));
                }
        var meshes = new ArrayList<Mesh>();
        var builder = new Builder();
        for (var triangle : order) {
            var chart = byTriangle[index(triangle, sources, offsets)];
            var surface = sources.get(triangle.surface());
            var identities = new ArrayList<Slot>();
            for (int corner = 0; corner < 3; corner++)
                identities.add(new Slot(
                        triangle.surface(), surface.indices().get(triangle.triangle() * 3 + corner), chart.chart()));
            int additions = 0;
            for (var identity : new java.util.HashSet<>(identities))
                if (!builder.slots.containsKey(identity)) additions++;
            if (!builder.indices.isEmpty() && builder.slots.size() + additions > layout.maxMeshSlots()) {
                meshes.add(builder.finish());
                builder = new Builder();
            }
            for (int corner = 0; corner < 3; corner++) {
                var identity = identities.get(corner);
                Integer local = builder.slots.get(identity);
                if (local == null) {
                    local = builder.positions.size();
                    builder.slots.put(identity, local);
                    builder.identities.add(identity);
                    builder.positions.add(surface.positions().get(identity.sourceSlot()));
                    builder.uvs.add(surface.textureUvs().get(identity.sourceSlot()));
                    var p = chart.corners().get(corner);
                    double x = (chart.chart() % layout.chartColumns()) * (layout.cells() + 1) + p.u() * layout.cells();
                    double y = (chart.chart() / layout.chartColumns()) * (layout.cells() + 1) + p.v() * layout.cells();
                    builder.local.add(new Point2((float) (x / columns), (float) (y / rows)));
                }
                builder.indices.add(local);
            }
            builder.triangles.add(triangle);
        }
        if (!builder.indices.isEmpty()) meshes.add(builder.finish());
        return new Plan(sources, meshes, columns, rows, controls, residual, samples);
    }

    private static int index(Triangle triangle, List<GlbSurfaceReader.Surface> sources, int[] offsets) {
        Objects.requireNonNull(triangle);
        if (triangle.surface() < 0
                || triangle.surface() >= sources.size()
                || triangle.triangle() < 0
                || triangle.triangle()
                        >= sources.get(triangle.surface()).indices().size() / 3)
            throw new IllegalArgumentException("unknown source triangle");
        return offsets[triangle.surface()] + triangle.triangle();
    }

    private static final class Builder {
        final HashMap<Slot, Integer> slots = new HashMap<>();
        final ArrayList<SurfaceProjection.Vertex> positions = new ArrayList<>();
        final ArrayList<Point2> uvs = new ArrayList<>(), local = new ArrayList<>();
        final ArrayList<Slot> identities = new ArrayList<>();
        final ArrayList<Integer> indices = new ArrayList<>();
        final ArrayList<Triangle> triangles = new ArrayList<>();

        Mesh finish() {
            return new Mesh(positions, uvs, local, identities, indices, triangles);
        }
    }
}
