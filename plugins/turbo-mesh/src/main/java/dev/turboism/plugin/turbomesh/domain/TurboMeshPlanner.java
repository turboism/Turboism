package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder;
import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder.MeshConstraints;
import dev.turboism.plugin.turbomesh.domain.features.FeatureField;
import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.plugin.turbomesh.domain.features.GeometryFeatureField;
import dev.turboism.plugin.turbomesh.domain.features.MeshFeatureAnalyzer;
import dev.turboism.plugin.turbomesh.domain.sampler.AdaptiveInteriorSampler;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one full custom re-placement for an existing ArtMesh: boundary rings are
 * curvature-adaptively resampled, the shape is segmented into autonomous regions, interior
 * sites are jittered per region, and everything is constrained-Delaunay triangulated. The
 * output is a complete {@link ArtMeshGeometry} whose positions are produced by
 * {@link UvToPosition} — barycentric where a source triangle covers the UV, affine otherwise.
 */
public final class TurboMeshPlanner {

    /** Diagnostics for logging/UI; counts refer to output arrays. */
    public record Stats(
            int regionCount,
            int boundaryPoints,
            int interiorPoints,
            int separatorEdges,
            int triangleCount,
            String presetName) {

        /** Legacy-shaped constructor; the preset name defaults to {@code LEGACY}. */
        public Stats(
                final int regionCount,
                final int boundaryPoints,
                final int interiorPoints,
                final int separatorEdges,
                final int triangleCount) {
            this(regionCount, boundaryPoints, interiorPoints, separatorEdges, triangleCount, "LEGACY");
        }
    }

    /** Complete replacement geometry plus run statistics. */
    public record PlacementPlan(ArtMeshGeometry geometry, Stats stats) {}

    /** Planning failures are fatal for the run — never write a half-planned mesh. */
    public static final class PlacementException extends Exception {
        public PlacementException(final String message) {
            super(message);
        }
    }

    private static final double DEDUP_EPS = 1.0e-6;
    private static final double ON_EDGE_EPS = 1.0e-6;

    private TurboMeshPlanner() {}

    /**
     * Geometry-only variant: the shape domain is the mesh's own UV footprint, so this works
     * without any texture pixel data. Boundary preservation comes from ring-edge constraints.
     */
    public static PlacementPlan plan(final ArtMeshGeometry source, final PlacementSettings settings)
            throws PlacementException {
        return plan(source, settings, FeatureOptions.disabled());
    }

    /** Geometry-only variant with the 0.2.0 feature path. */
    public static PlacementPlan plan(
            final ArtMeshGeometry source, final PlacementSettings settings, final FeatureOptions options)
            throws PlacementException {
        if (MeshTopology.boundaryLoops(source.triangleIndices()).isEmpty()) {
            throw new PlacementException("source mesh has no boundary ring to preserve");
        }
        final DomainMask mask;
        try {
            mask = DomainMask.rasterize(source.uvs(), source.triangleIndices(), settings.rasterMaxDim());
        } catch (IllegalArgumentException e) {
            throw new PlacementException("source mesh uv bounds are degenerate");
        }
        return plan(source, mask, settings, options);
    }

    /**
     * Full variant: the caller supplies the shape mask (e.g. built from texture alpha via
     * {@link DomainMask#fromPixels}). Same pipeline either way.
     */
    public static PlacementPlan plan(
            final ArtMeshGeometry source, final DomainMask mask, final PlacementSettings settings)
            throws PlacementException {
        return plan(source, mask, settings, FeatureOptions.disabled());
    }

    /**
     * Feature-aware variant: {@code options.enabled()} selects the 0.2.0 feature path (analyzed
     * corners, density-driven adaptive interior); when disabled the output is identical to the
     * 0.1.0 pipeline.
     */
    public static PlacementPlan plan(
            final ArtMeshGeometry source,
            final DomainMask mask,
            final PlacementSettings settings,
            final FeatureOptions options)
            throws PlacementException {
        final List<Point2> sourceUvs = source.uvs();
        final List<int[]> loops = MeshTopology.boundaryLoops(source.triangleIndices());
        if (loops.isEmpty()) {
            throw new PlacementException("source mesh has no boundary ring to preserve");
        }

        // 1. Region segmentation on the shape domain. In texture mode the geometric cuts are
        //    replaced by feature lines extracted from the artwork itself (line-art centerlines
        //    and colour boundaries), so segmentation only feeds the flat-area interior fill.
        final RegionSegmenter.Segmentation segmentation = RegionSegmenter.segment(mask, settings);
        final boolean textured =
                mask.hasTextureChannels() && !"false".equals(System.getProperty("turboMesh.textureEdges", "true"));
        final TextureEdges.Extraction extraction = textured ? TextureEdges.extractWithMask(mask, settings) : null;

        // 2. Boundary sampling — geometry-driven, not budgeted: each ring emits structural
        //    vertices, then curvature/length bounds decide how many more points it earns.
        final List<List<Point2>> rings = new ArrayList<>();
        for (final int[] loop : loops) {
            final List<Point2> ring = new ArrayList<>(loop.length);
            for (final int idx : loop) {
                ring.add(sourceUvs.get(idx));
            }
            if (ring.size() >= 3) {
                rings.add(ring);
            }
        }
        if (rings.isEmpty()) {
            throw new PlacementException("no usable boundary ring");
        }
        final boolean features = options.enabled();
        final MeshFeatureAnalyzer.Analysis analysis =
                features ? MeshFeatureAnalyzer.analyze(mask, rings, options) : null;
        final List<java.util.BitSet> protectedRings = new ArrayList<>();
        if (features) {
            for (int r = 0; r < rings.size(); r++) {
                protectedRings.add(new java.util.BitSet());
            }
            for (final List<MeshFeatureAnalyzer.Corner> ringCorners : analysis.corners()) {
                for (final MeshFeatureAnalyzer.Corner corner : ringCorners) {
                    protectedRings.get(corner.ring()).set(corner.vertex());
                }
            }
        }
        final List<Point2> sites = new ArrayList<>();
        final List<int[]> constraints = new ArrayList<>();
        final List<int[]> ringEdges = new ArrayList<>();
        final List<int[]> domainRings = new ArrayList<>();
        final List<List<Point2>> sampledPolys = new ArrayList<>();
        final List<BoundarySampler.SampledRing> sampledRings = new ArrayList<>();
        final Map<Long, Integer> dedup = new HashMap<>();
        int boundaryCount = 0;
        int interiorCount = 0;

        for (int r = 0; r < rings.size(); r++) {
            // Feature path, stage 1 — coarse the outline first: noisy auto-meshed rings collapse
            // to their coarse silhouette (detected corners locked) before the curvature passes
            // refine them. Legacy path keeps the verbatim ring.
            List<Point2> ringForSampling = rings.get(r);
            java.util.BitSet protectedVertices = features ? protectedRings.get(r) : null;
            if (features) {
                final BoundarySampler.Simplified coarse =
                        BoundarySampler.simplify(rings.get(r), settings.sagittaUv() * 3.0, protectedRings.get(r));
                ringForSampling = coarse.points();
                final java.util.BitSet mapped = new java.util.BitSet();
                for (int i = 0; i < coarse.originalIndices().length; i++) {
                    if (protectedRings.get(r).get(coarse.originalIndices()[i])) {
                        mapped.set(i);
                    }
                }
                protectedVertices = mapped;
            }
            final BoundarySampler.SampledRing sampled = BoundarySampler.resample(
                    ringForSampling, settings.maxBoundarySpacingUv(), settings, mask, protectedVertices);
            sampledRings.add(sampled);
            final List<Integer> ringIdx = new ArrayList<>(sampled.ring().size());
            for (final Point2 p : sampled.ring()) {
                ringIdx.add(siteIndex(sites, dedup, p));
            }
            sampledPolys.add(sampled.ring());
            domainRings.add(ringIdx.stream().mapToInt(Integer::intValue).toArray());
            for (int i = 0; i < ringIdx.size(); i++) {
                final int a = ringIdx.get(i);
                final int b = ringIdx.get((i + 1) % ringIdx.size());
                if (a != b) {
                    constraints.add(new int[] {a, b});
                    ringEdges.add(new int[] {a, b});
                }
            }
            boundaryCount += sampled.ring().size();
        }

        // The sampled rings ARE the output silhouette: classify silhouettes vs holes by
        // nesting depth so role decisions never hinge on source winding, then every interior
        // site and separator vertex is admitted only inside the domain.
        final List<List<Point2>> outerRings = new ArrayList<>();
        final List<List<Point2>> holeRings = new ArrayList<>();
        for (int i = 0; i < sampledPolys.size(); i++) {
            int depth = 0;
            for (int j = 0; j < sampledPolys.size(); j++) {
                if (i != j && ringHoldsRing(sampledPolys.get(j), sampledPolys.get(i))) {
                    depth++;
                }
            }
            (depth % 2 == 0 ? outerRings : holeRings).add(sampledPolys.get(i));
        }
        final double margin = settings.minSpacingUv();

        for (final BoundarySampler.SampledRing sampled : sampledRings) {
            for (final Point2 anchor : sampled.interiorAnchors()) {
                if (!insideDomain(outerRings, holeRings, anchor.x(), anchor.y())) {
                    continue; // anchors that fell outside the silhouette are noise
                }
                if (distToRings(sampledPolys, anchor.x(), anchor.y()) < margin) {
                    // Near-collinear with a ring edge: merge into the ring via snapping when
                    // possible, otherwise drop — either way no sliver vertex is planted.
                    nearestSite(sites, anchor, margin);
                    continue;
                }
                final int before = sites.size();
                siteIndex(sites, dedup, anchor);
                interiorCount += sites.size() - before;
            }
        }

        // 3. Constraint chains beyond the ring. Legacy mode emits extracted texture edge chains
        //    (or region separators) as hard constraints. Feature mode deliberately does not:
        //    artwork lines stay soft (density boost only) so the mesh is never torn along them —
        //    hard edges are the silhouette, holes and locked corners.
        int separatorEdgeCount = 0;
        if (features) {
            // no separator constraints; region separators only feed the interior fill below
        } else if (textured && extraction != null) {
            separatorEdgeCount += emitEdgeChains(
                    extraction.chains(), sites, dedup, constraints, sampledPolys, outerRings, holeRings, settings);
        } else {
            for (final List<Point2> polyline : segmentation.separators()) {
                if (polyline.size() < 2) {
                    continue;
                }
                for (final List<Point2> piece : clipToDomain(polyline, sampledPolys, outerRings, holeRings)) {
                    int prev = -1;
                    for (int i = 0; i < piece.size(); i++) {
                        Point2 p = piece.get(i);
                        final int idx;
                        if (i == 0 || i == piece.size() - 1) {
                            final int snap = nearestSite(sites, p, settings.minSpacingUv() * 4);
                            idx = snap >= 0 ? snap : siteIndex(sites, dedup, p);
                        } else if (distToRings(sampledPolys, p.x(), p.y()) < margin) {
                            continue; // too close to the silhouette: sliver vertex
                        } else {
                            idx = siteIndex(sites, dedup, p);
                        }
                        if (prev >= 0 && idx != prev) {
                            constraints.add(new int[] {prev, idx});
                            separatorEdgeCount++;
                        }
                        prev = idx;
                    }
                }
            }
        }

        // 4. Interior placement. Feature mode: density-driven adaptive sampling over the flat
        //    regions under the analyzed feature field, bounded by the remaining point budget.
        //    Legacy mode: fixed sparse grid per region. Everything keeps the global min-spacing
        //    rule either way.
        final List<RegionSegmenter.Region> fillRegions =
                textured ? flatRegions(mask, extraction.featureCells(), settings) : segmentation.regions();
        if (features) {
            final List<MeshFeatureAnalyzer.Corner> allCorners = new ArrayList<>();
            for (final List<MeshFeatureAnalyzer.Corner> ringCorners : analysis.corners()) {
                allCorners.addAll(ringCorners);
            }
            final MeshConstraints meshConstraints = MeshConstraintBuilder.build(sampledPolys, allCorners);
            final FeatureField field = new GeometryFeatureField(analysis, meshConstraints.hardEdges());
            final int budget = Math.max(0, settings.maxPoints() - sites.size());
            final int before = sites.size();
            for (final Point2 p : AdaptiveInteriorSampler.sample(mask, fillRegions, field, settings, options, budget)) {
                if (!insideDomain(outerRings, holeRings, p.x(), p.y())
                        || distToRings(sampledPolys, p.x(), p.y()) < margin
                        || nearestSite(sites, p, margin) >= 0) {
                    continue;
                }
                siteIndex(sites, dedup, p);
            }
            interiorCount += sites.size() - before;
            // Stage 3 — merge interior points that landed too close to an earlier site. Only
            // interior-added indices are dropped, so constraint (ring/chain) indices stay valid.
            final int preInterior = before;
            final double mergeTol = Math.max(settings.minSpacingUv(), settings.sagittaUv() * 2.0);
            final List<Point2> merged = new ArrayList<>(preInterior);
            for (int i = 0; i < preInterior; i++) {
                merged.add(sites.get(i));
            }
            for (int i = preInterior; i < sites.size(); i++) {
                final Point2 p = sites.get(i);
                if (nearestSite(merged, p, mergeTol) < 0) {
                    merged.add(p);
                }
            }
            interiorCount -= sites.size() - merged.size();
            sites.clear();
            sites.addAll(merged);
        } else {
            for (final RegionSegmenter.Region region : fillRegions) {
                final int before = sites.size();
                for (final Point2 p : InteriorSampler.sample(mask, region, settings.interiorSpacingUv(), settings)) {
                    if (!insideDomain(outerRings, holeRings, p.x(), p.y())
                            || distToRings(sampledPolys, p.x(), p.y()) < margin
                            || nearestSite(sites, p, margin) >= 0) {
                        continue;
                    }
                    siteIndex(sites, dedup, p);
                }
                interiorCount += sites.size() - before;
            }
        }

        if (sites.size() < 3) {
            throw new PlacementException("placement produced fewer than 3 points");
        }
        if (sites.size() > settings.maxPoints()) {
            throw new PlacementException("placement exceeded the point cap: " + sites.size());
        }

        // 5. Constrained Delaunay + domain filtering. Every boundary ring edge must survive:
        //    a missing one means the silhouette is no longer a closed point-line. The filter
        //    domain unions the rings so ring-covering triangles stay alive even when the
        //    alpha silhouette pulls inside the source outline.
        DomainMask domain = mask;
        for (final List<Point2> ring : rings) {
            domain = domain.unionRing(ring);
        }
        final ConstraintDelaunay.Result tri = ConstraintDelaunay.triangulate(sites, constraints, domain, domainRings);
        if (tri.triangleIndices().isEmpty()) {
            throw new PlacementException("triangulation produced no triangles");
        }
        requireClosedRings(tri.triangleIndices(), ringEdges, tri.points());

        // 6. UV -> position mapping for every output vertex.
        final UvToPosition map = UvToPosition.of(sourceUvs, source.positions(), source.triangleIndices());
        final List<Point2> positions = new ArrayList<>(tri.points().size());
        for (final Point2 uv : tri.points()) {
            positions.add(map.map(uv.x(), uv.y()));
        }
        final ArtMeshGeometry geometry =
                new ArtMeshGeometry(positions, List.copyOf(tri.points()), tri.triangleIndices());
        return new PlacementPlan(
                geometry,
                new Stats(
                        segmentation.regions().size(),
                        boundaryCount,
                        interiorCount,
                        separatorEdgeCount,
                        tri.triangleIndices().size() / 3,
                        options.preset().name()));
    }

    /**
     * Hard check that every boundary ring edge survives triangulation as a closed polyline on
     * the silhouette. A constraint that earned Steiner points is subdivided: then the edge is
     * satisfied by a chain of triangle edges whose nodes all sit on the straight segment
     * between the endpoints.
     */
    private static void requireClosedRings(
            final List<Integer> triangleIndices, final List<int[]> ringEdges, final List<Point2> points)
            throws PlacementException {
        if (ringEdges.isEmpty()) {
            return;
        }
        // Adjacency on triangle edges.
        final java.util.Map<Integer, java.util.List<Integer>> adjacency = new java.util.HashMap<>();
        for (int i = 0; i + 2 < triangleIndices.size(); i += 3) {
            for (int e = 0; e < 3; e++) {
                final int a = triangleIndices.get(i + e);
                final int b = triangleIndices.get(i + (e + 1) % 3);
                adjacency.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                adjacency.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            }
        }
        for (final int[] edge : ringEdges) {
            if (!subdividedEdgeExists(adjacency, points, edge[0], edge[1])) {
                throw new PlacementException("boundary ring is not closed: missing edge " + edge[0] + "-" + edge[1]);
            }
        }
    }

    /**
     * Depth-first walk along triangle edges from {@code a} to {@code b}, only stepping onto
     * nodes that lie on the straight segment a-b (tolerance accounts for float UVs). True iff a
     * conforming chain exists — an intact constraint edge is the trivial length-1 chain.
     */
    private static boolean subdividedEdgeExists(
            final java.util.Map<Integer, java.util.List<Integer>> adjacency,
            final List<Point2> points,
            final int a,
            final int b) {
        final Point2 pa = points.get(a);
        final Point2 pb = points.get(b);
        final double abx = pb.x() - pa.x();
        final double aby = pb.y() - pa.y();
        final double lenSq = abx * abx + aby * aby;
        final double tol = Math.max(1.0e-6, Math.sqrt(lenSq) * 1.0e-4);
        final java.util.Set<Integer> visited = new java.util.HashSet<>();
        final java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(a);
        while (!stack.isEmpty()) {
            final int node = stack.pop();
            if (node == b) {
                return true;
            }
            if (!visited.add(node)) {
                continue;
            }
            final Point2 pn = points.get(node);
            // Parametric position of pn projected onto the segment; must stay inside [0, 1).
            final double t = lenSq == 0 ? 0 : ((pn.x() - pa.x()) * abx + (pn.y() - pa.y()) * aby) / lenSq;
            final double perp = Math.abs((pn.x() - pa.x()) * aby - (pn.y() - pa.y()) * abx) / Math.sqrt(lenSq);
            if (t < -1.0e-4 || t > 1.0001 || perp > tol) {
                continue;
            }
            for (final int next : adjacency.getOrDefault(node, List.of())) {
                if (!visited.contains(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /**
     * Emits extracted texture edge chains as constraint polylines. Open endpoints are first
     * snapped onto an existing site (ring vertex, anchor, or an already-placed chain vertex),
     * then onto the nearest segment of another chain — the T-junction point is inserted into
     * that chain so the crossing chain shares a real vertex. Interior vertices closer than
     * {@code minSpacingUv} to an existing site merge into it.
     */
    private static int emitEdgeChains(
            final List<TextureEdges.EdgeChain> chains,
            final List<Point2> sites,
            final Map<Long, Integer> dedup,
            final List<int[]> constraints,
            final List<List<Point2>> sampledPolys,
            final List<List<Point2>> outerRings,
            final List<List<Point2>> holeRings,
            final PlacementSettings settings) {
        final double snapRadius = settings.minSpacingUv() * 4;
        final double margin = settings.minSpacingUv();
        final List<List<Point2>> polys = new ArrayList<>(chains.size());
        for (final TextureEdges.EdgeChain ch : chains) {
            polys.add(new ArrayList<>(ch.points()));
        }
        // T-snap open endpoints onto ring/site vertices or another chain's segment.
        for (int ci = 0; ci < chains.size(); ci++) {
            if (chains.get(ci).closed()) {
                continue;
            }
            final List<Point2> poly = polys.get(ci);
            for (int e = 0; e < 2 && poly.size() >= 2; e++) {
                final int vi = e == 0 ? 0 : poly.size() - 1;
                final Point2 p = poly.get(vi);
                final int snap = nearestSite(sites, p, snapRadius);
                if (snap >= 0) {
                    poly.set(vi, sites.get(snap));
                    continue;
                }
                final double[] hit = nearestChainSegment(polys, ci, p);
                if (hit != null && hit[3] < snapRadius) {
                    final int tci = (int) hit[0];
                    final int si = (int) hit[1];
                    final List<Point2> tgt = polys.get(tci);
                    Point2 proj = new Point2((float) hit[2], (float) hit[4]);
                    if (distTo(proj, tgt.get(si)) < DEDUP_EPS) {
                        proj = tgt.get(si);
                    } else if (si + 1 < tgt.size() && distTo(proj, tgt.get(si + 1)) < DEDUP_EPS) {
                        proj = tgt.get(si + 1);
                    } else {
                        tgt.add(si + 1, proj);
                    }
                    poly.set(vi, proj);
                }
            }
        }
        int emitted = 0;
        for (int ci = 0; ci < polys.size(); ci++) {
            final List<Point2> poly = polys.get(ci);
            if (poly.size() < 2) {
                continue;
            }
            final List<Point2> emitList = new ArrayList<>(poly);
            if (chains.get(ci).closed()) {
                emitList.add(poly.get(0)); // materialize the wrap-around segment for clipping
            }
            for (final List<Point2> piece : clipToDomain(emitList, sampledPolys, outerRings, holeRings)) {
                int prev = -1;
                for (int i = 0; i < piece.size(); i++) {
                    final Point2 p = piece.get(i);
                    final int idx;
                    if (i == 0 || i == piece.size() - 1) {
                        final int snap = nearestSite(sites, p, snapRadius);
                        idx = snap >= 0 ? snap : siteIndex(sites, dedup, p);
                    } else {
                        final int near = nearestSite(sites, p, margin);
                        idx = near >= 0 ? near : siteIndex(sites, dedup, p);
                    }
                    if (prev >= 0 && idx != prev) {
                        constraints.add(new int[] {prev, idx});
                        emitted++;
                    }
                    prev = idx;
                }
            }
        }
        return emitted;
    }

    /** Nearest point on any OTHER chain's polyline segments; {@code [chain, seg, px, dist, py]}. */
    private static double[] nearestChainSegment(final List<List<Point2>> polys, final int exclude, final Point2 p) {
        double[] best = null;
        for (int ci = 0; ci < polys.size(); ci++) {
            if (ci == exclude) {
                continue;
            }
            final List<Point2> poly = polys.get(ci);
            for (int i = 0; i + 1 < poly.size(); i++) {
                final Point2 a = poly.get(i);
                final Point2 b = poly.get(i + 1);
                final double ex = b.x() - a.x();
                final double ey = b.y() - a.y();
                final double len2 = ex * ex + ey * ey;
                double t = len2 < 1.0e-24 ? 0.0 : ((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / len2;
                t = Math.max(0.0, Math.min(1.0, t));
                final double px = a.x() + t * ex;
                final double py = a.y() + t * ey;
                final double d = Math.hypot(p.x() - px, p.y() - py);
                if (best == null || d < best[3]) {
                    best = new double[] {ci, i, px, d, py};
                }
            }
        }
        return best;
    }

    /**
     * Connected components of solid cells kept clear of every feature cell by at least
     * {@code interiorMarginUv} — the flat areas where texture-mode interior fill is allowed.
     */
    private static List<RegionSegmenter.Region> flatRegions(
            final DomainMask mask, final boolean[] featureCells, final PlacementSettings settings) {
        final int w = mask.width();
        final int h = mask.height();
        final int n = w * h;
        final int rad = Math.max(1, (int) Math.ceil(settings.interiorMarginUv() / mask.cellUv()));
        final boolean[] near = featureCells.clone();
        for (int pass = 0; pass < rad; pass++) {
            final boolean[] next = near.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int c = y * w + x;
                    if (!near[c]) {
                        continue;
                    }
                    if (x > 0) next[c - 1] = true;
                    if (x + 1 < w) next[c + 1] = true;
                    if (y > 0) next[c - w] = true;
                    if (y + 1 < h) next[c + w] = true;
                }
            }
            System.arraycopy(next, 0, near, 0, n);
        }
        final boolean[] flat = new boolean[n];
        for (int i = 0; i < n; i++) {
            flat[i] = mask.solidCell(i) && !near[i];
        }
        final List<RegionSegmenter.Region> regions = new ArrayList<>();
        final boolean[] seen = new boolean[n];
        int id = 0;
        for (int c = 0; c < n; c++) {
            if (!flat[c] || seen[c]) {
                continue;
            }
            final List<Integer> cells = new ArrayList<>();
            final java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(c);
            seen[c] = true;
            int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
            while (!q.isEmpty()) {
                final int cur = q.poll();
                cells.add(cur);
                final int x = cur % w;
                final int y = cur / w;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x > 0 && flat[cur - 1] && !seen[cur - 1]) {
                    seen[cur - 1] = true;
                    q.add(cur - 1);
                }
                if (x + 1 < w && flat[cur + 1] && !seen[cur + 1]) {
                    seen[cur + 1] = true;
                    q.add(cur + 1);
                }
                if (y > 0 && flat[cur - w] && !seen[cur - w]) {
                    seen[cur - w] = true;
                    q.add(cur - w);
                }
                if (y + 1 < h && flat[cur + w] && !seen[cur + w]) {
                    seen[cur + w] = true;
                    q.add(cur + w);
                }
            }
            final int[] cellArr = cells.stream().mapToInt(Integer::intValue).toArray();
            regions.add(new RegionSegmenter.Region(id++, cellArr, minX, minY, maxX, maxY));
        }
        return regions;
    }

    private static double distTo(final Point2 a, final Point2 b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    /**
     * Clips {@code polyline} to the material domain: each segment is cut at every proper
     * crossing with a ring edge, and only sub-segments whose midpoint sits inside the domain
     * survive. A piece ending at a ring crossing ends ON the boundary — the caller snaps such
     * endpoints onto ring sites.
     */
    private static List<List<Point2>> clipToDomain(
            final List<Point2> polyline,
            final List<List<Point2>> allRings,
            final List<List<Point2>> outerRings,
            final List<List<Point2>> holeRings) {
        final List<List<Point2>> pieces = new ArrayList<>();
        List<Point2> cur = null;
        for (int i = 0; i + 1 < polyline.size(); i++) {
            final Point2 p = polyline.get(i);
            final Point2 q = polyline.get(i + 1);
            final List<Double> cuts = new ArrayList<>();
            for (final List<Point2> ring : allRings) {
                for (int e = 0; e < ring.size(); e++) {
                    final Point2 x = ring.get(e);
                    final Point2 y = ring.get((e + 1) % ring.size());
                    final double t = segmentCrossParam(p, q, x, y);
                    if (t > 0.0 && t < 1.0) {
                        cuts.add(t);
                    }
                }
            }
            cuts.sort(Double::compareTo);
            final List<Double> bounds = new ArrayList<>();
            bounds.add(0.0);
            bounds.addAll(cuts);
            bounds.add(1.0);
            for (int k = 0; k + 1 < bounds.size(); k++) {
                final double s = bounds.get(k);
                final double e = bounds.get(k + 1);
                final double mid = (s + e) / 2.0;
                final double mu = p.x() + mid * (q.x() - p.x());
                final double mv = p.y() + mid * (q.y() - p.y());
                if (insideDomain(outerRings, holeRings, mu, mv)) {
                    if (cur == null) {
                        cur = new ArrayList<>();
                    }
                    final Point2 start = s == 0.0 ? p : at(p, q, s);
                    final Point2 end = e == 1.0 ? q : at(p, q, e);
                    if (cur.isEmpty() || !samePoint(cur.get(cur.size() - 1), start)) {
                        cur.add(start);
                    }
                    cur.add(end);
                } else if (cur != null) {
                    if (cur.size() >= 2) {
                        pieces.add(cur);
                    }
                    cur = null;
                }
            }
        }
        if (cur != null && cur.size() >= 2) {
            pieces.add(cur);
        }
        return pieces;
    }

    private static Point2 at(final Point2 p, final Point2 q, final double t) {
        return new Point2((float) (p.x() + t * (q.x() - p.x())), (float) (p.y() + t * (q.y() - p.y())));
    }

    private static boolean samePoint(final Point2 a, final Point2 b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y()) < DEDUP_EPS;
    }

    /** Param along {@code p-q} of its proper crossing with {@code x-y}, or {@code -1}. */
    private static double segmentCrossParam(final Point2 p, final Point2 q, final Point2 x, final Point2 y) {
        final double o1 = (q.x() - p.x()) * (x.y() - p.y()) - (q.y() - p.y()) * (x.x() - p.x());
        final double o2 = (q.x() - p.x()) * (y.y() - p.y()) - (q.y() - p.y()) * (y.x() - p.x());
        final double o3 = (y.x() - x.x()) * (p.y() - x.y()) - (y.y() - x.y()) * (p.x() - x.x());
        final double o4 = (y.x() - x.x()) * (q.y() - x.y()) - (y.y() - x.y()) * (q.x() - x.x());
        if (o1 * o2 >= 0.0 || o3 * o4 >= 0.0) {
            return -1.0; // no proper crossing
        }
        final double ex = y.x() - x.x();
        final double ey = y.y() - x.y();
        final double denom = (q.x() - p.x()) * ey - (q.y() - p.y()) * ex;
        if (Math.abs(denom) < 1.0e-20) {
            return -1.0;
        }
        return ((x.x() - p.x()) * ey - (x.y() - p.y()) * ex) / denom;
    }

    /** Whether {@code (u,v)} is inside at least one outer ring and strictly outside all holes. */
    private static boolean insideDomain(
            final List<List<Point2>> outerRings, final List<List<Point2>> holeRings, final double u, final double v) {
        boolean in = false;
        for (final List<Point2> ring : outerRings) {
            if (pointInOrOnRing(ring, u, v)) {
                in = true;
                break;
            }
        }
        if (!in) {
            return false;
        }
        for (final List<Point2> ring : holeRings) {
            if (pointInRing(ring, u, v) && distToRing(ring, u, v) > ON_EDGE_EPS) {
                return false;
            }
        }
        return true;
    }

    /** Minimum distance from {@code (u,v)} to any edge of any ring. */
    private static double distToRings(final List<List<Point2>> rings, final double u, final double v) {
        double best = Double.MAX_VALUE;
        for (final List<Point2> ring : rings) {
            best = Math.min(best, distToRing(ring, u, v));
        }
        return best;
    }

    private static double distToRing(final List<Point2> ring, final double u, final double v) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < ring.size(); i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get((i + 1) % ring.size());
            final double ex = b.x() - a.x();
            final double ey = b.y() - a.y();
            final double lenSq = ex * ex + ey * ey;
            final double t =
                    lenSq < 1.0e-24 ? 0.0 : Math.max(0.0, Math.min(1.0, ((u - a.x()) * ex + (v - a.y()) * ey) / lenSq));
            best = Math.min(best, Math.hypot(u - (a.x() + t * ex), v - (a.y() + t * ey)));
        }
        return best;
    }

    /** Even-odd containment or boundary graze within {@link #ON_EDGE_EPS}. */
    private static boolean pointInOrOnRing(final List<Point2> ring, final double u, final double v) {
        return pointInRing(ring, u, v) || distToRing(ring, u, v) <= ON_EDGE_EPS;
    }

    /** Even-odd ray-cast containment (no boundary tolerance). */
    private static boolean pointInRing(final List<Point2> ring, final double u, final double v) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get(j);
            if ((a.y() > v) != (b.y() > v) && u < (b.x() - a.x()) * (v - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Whether {@code inner}'s mid-edge point lies inside {@code ring}. */
    private static boolean ringHoldsRing(final List<Point2> ring, final List<Point2> inner) {
        final Point2 a = inner.get(0);
        final Point2 b = inner.get(1 % inner.size());
        return pointInOrOnRing(ring, (a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0);
    }

    /**
     * Returns the index of {@code p} in {@code sites}, inserting it unless an existing site
     * sits within the dedup tolerance.
     */
    private static int siteIndex(final List<Point2> sites, final Map<Long, Integer> dedup, final Point2 p) {
        final long key = dedupKey(p);
        final Integer hit = dedup.get(key);
        if (hit != null) {
            return hit;
        }
        // Neighborhood check against adjacent buckets to catch near-misses across borders.
        final long bx = (long) Math.floor(p.x() / DEDUP_EPS);
        final long by = (long) Math.floor(p.y() / DEDUP_EPS);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                final Integer idx = dedup.get((bx + dx) * 1000003L + (by + dy));
                if (idx != null) {
                    final Point2 q = sites.get(idx);
                    if (Math.hypot(q.x() - p.x(), q.y() - p.y()) < DEDUP_EPS) {
                        return idx;
                    }
                }
            }
        }
        dedup.put(key, sites.size());
        sites.add(p);
        return sites.size() - 1;
    }

    private static long dedupKey(final Point2 p) {
        return (long) Math.floor(p.x() / DEDUP_EPS) * 1000003L + (long) Math.floor(p.y() / DEDUP_EPS);
    }

    private static int nearestSite(final List<Point2> sites, final Point2 p, final double maxDist) {
        int best = -1;
        double bestD = maxDist;
        for (int i = 0; i < sites.size(); i++) {
            final Point2 q = sites.get(i);
            final double d = Math.hypot(q.x() - p.x(), q.y() - p.y());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
