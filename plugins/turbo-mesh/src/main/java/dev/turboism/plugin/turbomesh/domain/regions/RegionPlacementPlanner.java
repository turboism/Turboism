package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.plugin.turbomesh.domain.ConstraintDelaunay;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Plans a texture-driven constraint mesh for one image.
 *
 * <p>Stages: alpha crop, nested region segmentation, shared border extraction, direction guides,
 * node unification, interior fill, near-point averaging, then constrained Delaunay triangulation
 * with the plugin's own triangulator. The planner keeps no static mutable state, so callers may
 * run it concurrently.</p>
 *
 * <p>Every stage may damage the domain silently, so the planner verifies the domain rings and the
 * triangle coverage of each attempt and retries without the stage it suspects. A result that
 * misses the coverage contract is never returned; the planner throws instead.</p>
 */
public final class RegionPlacementPlanner {

    /** Pixel-to-UV divisor that puts planning coordinates in the triangulator's usual magnitude. */
    static final double PIXELS_PER_UV = 1024.0;

    /** Lowest share of the domain area that the returned triangles must cover. */
    static final double MIN_COVERAGE_RATIO = 0.97;

    /** Retry ladder: each entry names the stage the attempt leaves out. */
    private static final List<RegionPlacementPlan.Fallback> RETRY_LADDER = List.of(
            RegionPlacementPlan.Fallback.NONE,
            RegionPlacementPlan.Fallback.MERGE_DISABLED,
            RegionPlacementPlan.Fallback.SIMPLIFICATION_DISABLED);

    private RegionPlacementPlanner() {}

    /**
     * Plans the mesh for one image.
     *
     * @param argb non-premultiplied ARGB pixels, row-major
     * @param params placement knobs; every auto value follows the silhouette span
     * @return mesh plus the raster and metrics that produced it
     * @throws IllegalArgumentException when the image or the params cannot be planned
     * @throws IllegalStateException when no attempt satisfies the coverage contract
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
        final List<String> rejections = new ArrayList<>(RETRY_LADDER.size());
        for (final RegionPlacementPlan.Fallback fallback : RETRY_LADDER) {
            final Attempt attempt = planOnce(raster, resolved, labels, fallback);
            checkInterrupted();
            if (attempt.failure() == null) {
                return attempt.candidate().toPlan(labels, raster, fallback);
            }
            rejections.add(fallback + ": " + attempt.failure());
        }
        throw new IllegalStateException(
                "every planning attempt missed the mesh contract: " + String.join("; ", rejections));
    }

    private static Attempt planOnce(
            final RegionRaster raster,
            final RegionPlacementParams.Resolved resolved,
            final int[] labels,
            final RegionPlacementPlan.Fallback fallback) {
        final boolean mergeEnabled = fallback == RegionPlacementPlan.Fallback.NONE;
        final boolean simplifyEnabled = fallback != RegionPlacementPlan.Fallback.SIMPLIFICATION_DISABLED;
        final double tolerance = simplifyEnabled ? resolved.simplifyTolerance() : 0.0;
        final List<BoundaryPath> borders =
                SharedBoundaryPaths.extract(labels, raster.width(), raster.height(), tolerance, resolved.maxSpacing());
        final PolygonShape domain = DomainOutline.of(borders);
        if (domain == null) {
            throw new IllegalArgumentException("silhouette outline does not close, so no domain exists");
        }
        final DirectionGuides.Guides guides =
                DirectionGuides.generate(raster, domain, resolved.maxSpacing(), resolved.trackCount());
        checkInterrupted();
        final SiteGraph graph = new SiteGraph();
        for (final BoundaryPath path : PolylineNoder.node(combined(borders, guides.paths()))) {
            graph.addPath(path.line(), path.role());
        }
        graph.sealBoundary();
        RegionInteriorFill.fill(
                graph,
                labels,
                raster.width(),
                raster.height(),
                raster.boundaryClearance(labels),
                resolved.interiorSpacing(),
                domain);
        final List<int[]> domainLoops = domainRings(domain, graph);
        if (domainLoops == null) {
            return new Attempt(null, "a domain ring edge has no matching mesh edge chain");
        }
        final int beforeMerge = graph.siteCount();
        final SiteGraph.Snapshot filled = graph.snapshot().withRings(domainLoops);
        checkInterrupted();
        final SiteGraph.Snapshot merged = mergeEnabled ? SiteMerger.merge(filled, resolved.minSpacing()) : filled;
        final List<int[]> rings = merged.rings();
        final DomainRingAudit.Verdict verdict = DomainRingAudit.check(merged, rings, domain.area());
        if (verdict != DomainRingAudit.Verdict.PASSED) {
            return new Attempt(null, "domain rings failed the audit with " + verdict);
        }
        checkInterrupted();
        final ConstraintDelaunay.Result mesh = triangulate(merged, rings);
        final Candidate candidate =
                Candidate.of(mesh, merged, rings, constraints(merged), beforeMerge, guides.family());
        if (candidate.triangleCount() == 0) {
            return new Attempt(null, "triangulation returned no triangle");
        }
        if (candidate.coverage() < MIN_COVERAGE_RATIO) {
            return new Attempt(
                    null,
                    String.format(
                            "triangles cover %.3f of the domain (%.1f of %.1f px^2) with %d unreferenced vertices",
                            candidate.coverage(),
                            candidate.meshArea(),
                            candidate.ringArea(),
                            candidate.droppedVertices()));
        }
        return new Attempt(candidate, null);
    }

    private static List<BoundaryPath> combined(final List<BoundaryPath> borders, final List<BoundaryPath> guides) {
        final List<BoundaryPath> combined = new ArrayList<>(borders.size() + guides.size());
        combined.addAll(borders);
        combined.addAll(guides);
        return combined;
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
     * Registers every domain ring vertex as a site index loop for the triangulator.
     *
     * <p>Noding splits a border edge wherever a guide meets it, so a ring edge taken straight from
     * the polygon would name a pair with no mesh edge. The loop therefore follows the graph nodes
     * that sit on each ring edge, which keeps every published ring edge a real constraint edge.</p>
     *
     * @return one index loop per domain ring, or {@code null} when a ring edge cannot be followed
     */
    private static List<int[]> domainRings(final PolygonShape domain, final SiteGraph graph) {
        final List<int[]> rings = new ArrayList<>(domain.rings().size());
        for (final Polyline ring : domain.rings()) {
            final int[] anchors = new int[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                anchors[i] = graph.site(ring.x(i), ring.y(i));
            }
            final List<Integer> loop = new ArrayList<>(anchors.length * 2);
            for (int i = 0; i < anchors.length; i++) {
                if (!appendEdgeChain(graph, anchors[i], anchors[(i + 1) % anchors.length], loop)) {
                    return null;
                }
            }
            rings.add(toPacked(loop));
        }
        return rings;
    }

    /**
     * Appends the sites that a graph chain visits between two ring anchors.
     *
     * @return false when no chain of edges joins the anchors
     */
    private static boolean appendEdgeChain(
            final SiteGraph graph, final int from, final int to, final List<Integer> out) {
        final double targetX = graph.x(to);
        final double targetY = graph.y(to);
        final double directionX = targetX - graph.x(from);
        final double directionY = targetY - graph.y(from);
        final double span = Math.hypot(directionX, directionY);
        int current = from;
        out.add(current);
        int guard = graph.siteCount();
        while (current != to && guard-- > 0) {
            final int next = nextAlong(graph, current, to, directionX, directionY, span);
            if (next < 0) {
                return false;
            }
            current = next;
            if (current != to) {
                out.add(current);
            }
        }
        return current == to;
    }

    /**
     * Picks the graph neighbour that advances furthest along the ring edge towards {@code to}.
     *
     * <p>Only neighbours that sit on the ring edge count. A guide endpoint on that edge is the
     * usual case; any other neighbour would break the ring's straight run.</p>
     */
    private static int nextAlong(
            final SiteGraph graph,
            final int current,
            final int to,
            final double directionX,
            final double directionY,
            final double span) {
        int best = -1;
        double bestProgress = 0.0;
        for (final int neighbour : graph.neighbours(current)) {
            if (neighbour == to) {
                return to;
            }
            final double progress = PixelGeometry.projection(
                    graph.x(current),
                    graph.y(current),
                    graph.x(current) + directionX,
                    graph.y(current) + directionY,
                    graph.x(neighbour),
                    graph.y(neighbour));
            final double offLine = PixelGeometry.lineDistance(
                    graph.x(current),
                    graph.y(current),
                    graph.x(current) + directionX,
                    graph.y(current) + directionY,
                    graph.x(neighbour),
                    graph.y(neighbour));
            if (offLine > PixelGeometry.ON_SEGMENT_EPS || progress <= 0.0 || progress * span >= span) {
                continue;
            }
            if (progress > bestProgress) {
                bestProgress = progress;
                best = neighbour;
            }
        }
        return best;
    }

    private static int[] toPacked(final List<Integer> loop) {
        int size = loop.size();
        if (size > 1 && loop.get(0).equals(loop.get(size - 1))) {
            size--;
        }
        final int[] packed = new int[size];
        for (int i = 0; i < size; i++) {
            packed[i] = loop.get(i);
        }
        return packed;
    }

    private static List<RegionPlacementPlan.Constraint> constraints(final SiteGraph.Snapshot merged) {
        final List<RegionPlacementPlan.Constraint> constraints = new ArrayList<>(merged.edgeCount());
        for (int e = 0; e < merged.edgeCount(); e++) {
            constraints.add(
                    new RegionPlacementPlan.Constraint(merged.edgeA()[e], merged.edgeB()[e], merged.edgeRoles()[e]));
        }
        return constraints;
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

    /** Outcome of one attempt; {@code failure} names the contract breach and is null on success. */
    private record Attempt(Candidate candidate, String failure) {}

    /**
     * Triangulated attempt with its coverage numbers.
     *
     * <p>The candidate already dropped every vertex that no triangle uses, so the caller may trust
     * {@link #vertices()} to be exactly the referenced set.</p>
     */
    private record Candidate(
            List<Point2> rasterVertices,
            int[] triangles,
            List<RegionPlacementPlan.Constraint> constraints,
            List<int[]> rings,
            double meshArea,
            double ringArea,
            int droppedVertices,
            int vertexCountBeforeMerge,
            ShapeFamily shapeFamily) {

        static Candidate of(
                final ConstraintDelaunay.Result mesh,
                final SiteGraph.Snapshot merged,
                final List<int[]> rings,
                final List<RegionPlacementPlan.Constraint> constraints,
                final int vertexCountBeforeMerge,
                final ShapeFamily shapeFamily) {
            final int[] raw = toArray(mesh.triangleIndices());
            final List<Point2> points = mesh.points();
            final int[] remap = usedRemap(raw, points.size());
            final List<Point2> kept = remap == null ? points : compactPoints(points, remap);
            final int[] triangles = remap == null ? raw : remapTriangles(raw, remap);
            final int dropped = points.size() - kept.size();
            final List<Point2> raster = rasterPoints(kept);
            return new Candidate(
                    raster,
                    triangles,
                    remap == null ? constraints : remapConstraints(constraints, remap),
                    remap == null ? rings : remapRings(rings, remap),
                    meshArea(raster, triangles),
                    DomainRingAudit.enclosedArea(merged, rings),
                    dropped,
                    vertexCountBeforeMerge,
                    shapeFamily);
        }

        int triangleCount() {
            return triangles.length / 3;
        }

        double coverage() {
            return ringArea <= 0.0 ? 0.0 : meshArea / ringArea;
        }

        RegionPlacementPlan toPlan(
                final int[] labels, final RegionRaster raster, final RegionPlacementPlan.Fallback fallback) {
            final List<Point2> vertices = new ArrayList<>(rasterVertices.size());
            for (final Point2 vertex : rasterVertices) {
                vertices.add(
                        new Point2((float) (vertex.x() + raster.originX()), (float) (vertex.y() + raster.originY())));
            }
            return new RegionPlacementPlan(
                    vertices,
                    triangles,
                    constraints,
                    rings,
                    new RegionPlacementPlan.RegionGrid(
                            labels, raster.width(), raster.height(), raster.originX(), raster.originY()),
                    vertexCountBeforeMerge,
                    vertices.size(),
                    PixelCoverage.triangleIou(
                            rasterVertices, triangles, raster.mask(), raster.width(), raster.height()),
                    shapeFamily,
                    fallback);
        }

        /** Index of every raw triangle vertex, or {@code null} when every vertex is referenced. */
        private static int[] usedRemap(final int[] triangles, final int vertexCount) {
            final boolean[] used = new boolean[vertexCount];
            int referenced = 0;
            for (final int index : triangles) {
                if (!used[index]) {
                    used[index] = true;
                    referenced++;
                }
            }
            if (referenced == vertexCount) {
                return null;
            }
            final int[] remap = new int[vertexCount];
            Arrays.fill(remap, -1);
            int next = 0;
            for (int i = 0; i < vertexCount; i++) {
                if (used[i]) {
                    remap[i] = next++;
                }
            }
            return remap;
        }

        private static List<Point2> compactPoints(final List<Point2> points, final int[] remap) {
            final List<Point2> kept = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                if (remap[i] >= 0) {
                    kept.add(points.get(i));
                }
            }
            return List.copyOf(kept);
        }

        private static int[] remapTriangles(final int[] triangles, final int[] remap) {
            final int[] out = new int[triangles.length];
            for (int i = 0; i < triangles.length; i++) {
                out[i] = remap[triangles[i]];
            }
            return out;
        }

        /** Drops constraints whose endpoints left the mesh; a half edge cannot be honoured. */
        private static List<RegionPlacementPlan.Constraint> remapConstraints(
                final List<RegionPlacementPlan.Constraint> constraints, final int[] remap) {
            final List<RegionPlacementPlan.Constraint> out = new ArrayList<>(constraints.size());
            for (final RegionPlacementPlan.Constraint constraint : constraints) {
                final int a = remap[constraint.a()];
                final int b = remap[constraint.b()];
                if (a >= 0 && b >= 0) {
                    out.add(new RegionPlacementPlan.Constraint(a, b, constraint.role()));
                }
            }
            return List.copyOf(out);
        }

        private static List<int[]> remapRings(final List<int[]> rings, final int[] remap) {
            final List<int[]> out = new ArrayList<>(rings.size());
            for (final int[] ring : rings) {
                final List<Integer> kept = new ArrayList<>(ring.length);
                for (final int vertex : ring) {
                    if (remap[vertex] >= 0) {
                        kept.add(remap[vertex]);
                    }
                }
                if (kept.size() >= 3) {
                    final int[] packed = new int[kept.size()];
                    for (int i = 0; i < packed.length; i++) {
                        packed[i] = kept.get(i);
                    }
                    out.add(packed);
                }
            }
            return List.copyOf(out);
        }

        /** Vertices in cropped raster pixel space, which is the space of the alpha mask. */
        private static List<Point2> rasterPoints(final List<Point2> uvPoints) {
            final List<Point2> points = new ArrayList<>(uvPoints.size());
            for (final Point2 uv : uvPoints) {
                points.add(new Point2((float) (uv.x() * PIXELS_PER_UV), (float) (uv.y() * PIXELS_PER_UV)));
            }
            return List.copyOf(points);
        }

        private static double meshArea(final List<Point2> points, final int[] triangles) {
            double area = 0.0;
            for (int t = 0; t + 2 < triangles.length; t += 3) {
                final Point2 a = points.get(triangles[t]);
                final Point2 b = points.get(triangles[t + 1]);
                final Point2 c = points.get(triangles[t + 2]);
                area += Math.abs((b.x() - a.x()) * (c.y() - a.y()) - (c.x() - a.x()) * (b.y() - a.y())) / 2.0;
            }
            return area;
        }
    }
}
