package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.plugin.turbomesh.domain.ConstraintDelaunay;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Plans a texture-driven constraint mesh for one image.
 *
 * <p>Stages: alpha crop, nested region segmentation, shared border extraction, direction guides,
 * node unification, interior fill, near-point averaging, then constrained Delaunay triangulation
 * with the plugin's own triangulator. The planner keeps no static mutable state, so callers may
 * run it concurrently.</p>
 */
public final class RegionPlacementPlanner {

    /** Pixel-to-UV divisor that puts planning coordinates in the triangulator's usual magnitude. */
    static final double PIXELS_PER_UV = 1024.0;

    private RegionPlacementPlanner() {}

    /**
     * Plans the mesh for one image.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @param params placement knobs; every auto value follows the silhouette span
     * @return mesh plus the raster and metrics that produced it
     * @throws IllegalArgumentException when the image or the params cannot be planned
     * @throws CancellationException when the calling thread was interrupted between stages
     */
    public static RegionPlacementPlan plan(
            final int[] argb, final int width, final int height, final RegionPlacementParams params) {
        params.validate();
        final RegionRaster raster = RegionRaster.cropped(argb, width, height);
        final RegionPlacementParams.Resolved resolved = params.resolve(raster.silhouetteSpan());
        checkInterrupted();
        final TextureRegionSegmenter.Levels levels =
                TextureRegionSegmenter.segment(raster, resolved.minRegionRatio(), resolved.contrast());
        final int[] labels = levels.level(resolved.level());
        checkInterrupted();
        final List<BoundaryPath> borders = SharedBoundaryPaths.extract(
                labels, raster.width(), raster.height(), resolved.simplifyTolerance(), resolved.maxSpacing());
        final PolygonShape domain = DomainOutline.of(borders);
        if (domain == null) {
            throw new IllegalArgumentException("silhouette outline does not close, so no domain exists");
        }
        final DirectionGuides.Guides guides =
                DirectionGuides.generate(raster, domain, resolved.maxSpacing(), resolved.trackCount());
        checkInterrupted();
        final List<BoundaryPath> combined =
                new ArrayList<>(borders.size() + guides.paths().size());
        combined.addAll(borders);
        combined.addAll(guides.paths());
        final SiteGraph graph = new SiteGraph();
        for (final BoundaryPath path : PolylineNoder.node(combined)) {
            graph.addPath(path.line(), path.role());
        }
        graph.sealBoundary();
        checkInterrupted();
        RegionInteriorFill.fill(
                graph,
                labels,
                raster.width(),
                raster.height(),
                raster.boundaryClearance(labels),
                resolved.interiorSpacing(),
                domain);
        final int beforeMerge = graph.siteCount();
        final SiteGraph.Snapshot filled = graph.snapshot().withRings(domainRings(domain, graph));
        checkInterrupted();
        final SiteGraph.Snapshot merged = SiteMerger.merge(filled, resolved.minSpacing());
        checkInterrupted();
        final List<int[]> rings = outlineRings(merged);
        final ConstraintDelaunay.Result mesh = triangulate(merged, rings);
        checkInterrupted();
        return new RegionPlacementPlan(
                sourceVertices(mesh.points(), raster.originX(), raster.originY()),
                toArray(mesh.triangleIndices()),
                constraints(merged),
                rings,
                new RegionPlacementPlan.RegionGrid(
                        labels, raster.width(), raster.height(), raster.originX(), raster.originY()),
                beforeMerge,
                merged.siteCount(),
                PixelCoverage.alphaIou(domain, raster.mask(), raster.width(), raster.height()),
                guides.family());
    }

    private static ConstraintDelaunay.Result triangulate(final SiteGraph.Snapshot merged, final List<int[]> rings) {
        final List<Point2> points = new ArrayList<>(merged.siteCount());
        for (int i = 0; i < merged.siteCount(); i++) {
            points.add(new Point2((float) (merged.x(i) / PIXELS_PER_UV), (float) (merged.y(i) / PIXELS_PER_UV)));
        }
        final List<int[]> edges = new ArrayList<>(merged.edgeCount());
        for (int e = 0; e < merged.edgeCount(); e++) {
            edges.add(new int[] {merged.edgeA()[e], merged.edgeB()[e]});
        }
        return ConstraintDelaunay.triangulate(points, edges, null, rings);
    }

    /**
     * Rebuilds the domain rings from the surviving outline edges.
     *
     * <p>Averaging moves outline vertices, so the rings must follow the merged geometry and keep
     * only edges that still exist; a ring that fails to close would hide triangles silently.</p>
     */
    private static List<int[]> outlineRings(final SiteGraph.Snapshot merged) {
        final List<int[]> outlineEdges = new ArrayList<>();
        for (int e = 0; e < merged.edgeCount(); e++) {
            if (merged.edgeRoles()[e] == ConstraintRole.OUTLINE) {
                outlineEdges.add(new int[] {merged.edgeA()[e], merged.edgeB()[e]});
            }
        }
        final List<int[]> rings = DomainOutline.chainedLoops(outlineEdges);
        if (rings.isEmpty()) {
            throw new IllegalStateException("merged outline edges close no ring");
        }
        return rings;
    }

    private static List<Point2> sourceVertices(final List<Point2> uvs, final int originX, final int originY) {
        final List<Point2> vertices = new ArrayList<>(uvs.size());
        for (final Point2 uv : uvs) {
            vertices.add(
                    new Point2((float) (uv.x() * PIXELS_PER_UV + originX), (float) (uv.y() * PIXELS_PER_UV + originY)));
        }
        return vertices;
    }

    private static List<RegionPlacementPlan.Constraint> constraints(final SiteGraph.Snapshot merged) {
        final List<RegionPlacementPlan.Constraint> constraints = new ArrayList<>(merged.edgeCount());
        for (int e = 0; e < merged.edgeCount(); e++) {
            constraints.add(
                    new RegionPlacementPlan.Constraint(merged.edgeA()[e], merged.edgeB()[e], merged.edgeRoles()[e]));
        }
        return constraints;
    }

    /** Registers every domain ring vertex as a site index loop for the triangulator. */
    private static List<int[]> domainRings(final PolygonShape domain, final SiteGraph graph) {
        final List<int[]> rings = new ArrayList<>(domain.rings().size());
        for (final Polyline ring : domain.rings()) {
            final int[] indices = new int[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                indices[i] = graph.site(ring.x(i), ring.y(i));
            }
            rings.add(indices);
        }
        return rings;
    }

    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("texture region planning was interrupted");
        }
    }
}
