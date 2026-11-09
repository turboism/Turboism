package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tinfour.common.IConstraint;
import org.tinfour.common.IQuadEdge;
import org.tinfour.common.LinearConstraint;
import org.tinfour.common.SimpleTriangle;
import org.tinfour.common.Vertex;
import org.tinfour.standard.IncrementalTin;

/**
 * Constrained Delaunay triangulation in UV space, backed by the Tinfour kernel.
 *
 * <p>The Tinfour incremental TIN is the triangulation engine. Every site becomes a Tinfour vertex
 * that keeps its caller index, and every requested index pair becomes a linear constraint. A segment
 * that passes through other sites is named as a chain, so Tinfour never has to infer the split. The
 * mesh is then read back as counter-clockwise triangles.</p>
 *
 * <p>Two filters run after triangulation, and neither one may drop a triangle that carries a
 * constrained edge: such an edge is load-bearing for the caller's silhouette, separator or ring
 * loop, and losing its last triangle would reopen the boundary the caller guaranteed. The domain
 * filter keeps triangles whose centroid lies inside a silhouette ring and outside every hole ring.
 * The mask filter keeps triangles whose centroid the raster domain accepts. Both tests classify a
 * triangle by its centroid, so a ring or mask edge grazing the centroid counts as inside.</p>
 *
 * <p>Collinear sites can leave a zero-area sliver in a Delaunay mesh. Such a triangle has no
 * orientation, so it is re-triangulated into its neighbour or dropped before any filter runs.</p>
 *
 * <p>Index identity is part of the contract: the returned point list starts with the caller's
 * points in the caller's order, so an index that entered the call stays valid in the result.
 * Vertices Tinfour adds itself are appended after them.</p>
 *
 * <p>A site set with fewer than three non-collinear points admits no triangle. Such a set — and a
 * set Tinfour refuses to bootstrap — returns the caller's points with an empty triangle list
 * instead of an error.</p>
 */
public final class ConstraintDelaunay {

    /** Distance (UV units) below which a point counts as lying on a ring edge. */
    private static final double ON_EPS = 1.0e-6;

    /** Vertices of one triangle, and the smallest site set that can form one. */
    private static final int TRIANGLE_VERTEX_COUNT = 3;

    /** Endpoints of one constraint segment, as the caller indexes them. */
    private static final int SEGMENT_ENDPOINT_COUNT = 2;

    /** Divisor turning a vertex sum into a vertex average. */
    private static final double VERTEX_AVERAGE_DIVISOR = 3.0;

    /** Ring area magnitude below which a loop is too thin to classify a domain. */
    private static final double MIN_RING_AREA = 1.0e-12;

    /** Squared edge length below which a ring edge counts as collapsed. */
    private static final double COLLAPSED_LENGTH_SQ = 1.0e-24;

    /**
     * A triangle whose height above its longest edge is below this cannot be published. The site
     * coincidence tolerance already merges anything closer than {@link #ON_EPS}, so a triangle that
     * thin is a rounding artifact of a collinear site pattern rather than mesh area.
     */
    private static final double COLLAPSED_HEIGHT = ON_EPS;

    private ConstraintDelaunay() {}

    /**
     * @param points sites surviving in the output plus any synthetic vertices appended
     * @param triangleIndices flat index triples into {@code points}, counter-clockwise
     */
    public record Result(List<Point2> points, List<Integer> triangleIndices) {}

    /**
     * Convenience overload with no domain rings: everything inside the convex hull of the
     * sites survives — only the {@code mask} filters.
     */
    public static Result triangulate(final List<Point2> points, final List<int[]> constraints, final DomainMask mask) {
        return triangulate(points, constraints, mask, List.of());
    }

    /**
     * @param points UV sites; may be sampled boundary, anchors, separator and interior points
     * @param constraints pairs of indices into {@code points} that must appear as edges
     * @param mask domain test for triangle filtering, or {@code null} to keep everything
     * @param domainRings closed site-index loops bounding the material domain: positive-area
     *     rings are silhouettes, negative-area rings are holes; may be empty
     */
    public static Result triangulate(
            final List<Point2> points,
            final List<int[]> constraints,
            final DomainMask mask,
            final List<int[]> domainRings) {
        final Mesh mesh = new Mesh(points);
        buildConstrainedTin(mesh, constraints);
        repairCollapsedTriangles(mesh);
        classifyDomain(mesh, domainRings);
        if (mask != null) {
            applyMask(mesh, mask);
        }
        return new Result(List.copyOf(mesh.pts), mesh.flattenTriangles());
    }

    /**
     * Triangulates the sites with Tinfour and fills the mesh with counter-clockwise triangles plus
     * the ledger of edges that carry a constraint.
     */
    private static void buildConstrainedTin(final Mesh mesh, final List<int[]> constraints) {
        if (mesh.pts.size() < TRIANGLE_VERTEX_COUNT) {
            return;
        }
        final Bounds bounds = Bounds.of(mesh.pts);
        final double spacing = bounds.nominalSpacing(mesh.pts.size());
        if (spacing <= 0.0) {
            return; // every site shares one axis, so no triangle can have area
        }
        final IncrementalTin tin = new IncrementalTin(spacing);
        final List<Vertex> vertices = new ArrayList<>(mesh.pts.size());
        for (int i = 0; i < mesh.pts.size(); i++) {
            final Point2 p = mesh.pts.get(i);
            final Vertex vertex = new Vertex(p.x(), p.y(), 0.0, i);
            mesh.vertexToSite.put(vertex, i);
            vertices.add(vertex);
        }
        if (!tin.add(vertices, null)) {
            return; // degenerate site set: Tinfour found no seed triangle
        }
        final List<IConstraint> segments = segments(mesh, vertices, constraints);
        if (!segments.isEmpty()) {
            // Conformity restoration is off on purpose: Tinfour restores it by halving a
            // constrained edge, which injects synthetic vertices the caller's vertex contract
            // forbids. The rest of the mesh already satisfies the Delaunay criterion, so only the
            // triangles next to a constraint carry the ordering the carve produced.
            tin.addConstraints(segments, false);
        }
        readTriangles(mesh, tin);
    }

    /**
     * Turns the requested index pairs into Tinfour constraints. A pair that names the same site
     * twice, an out-of-range index or a zero-length segment carries no constraint and is dropped:
     * Tinfour cannot carve it, and one unusable segment would fail the whole set.
     */
    private static List<IConstraint> segments(
            final Mesh mesh, final List<Vertex> vertices, final List<int[]> constraints) {
        final List<IConstraint> out = new ArrayList<>(constraints.size());
        if (constraints.isEmpty()) {
            return out;
        }
        final int[] byU = sitesSortedBy(mesh.pts, true);
        final int[] byV = sitesSortedBy(mesh.pts, false);
        for (final int[] pair : constraints) {
            if (pair == null || pair.length < SEGMENT_ENDPOINT_COUNT) {
                continue;
            }
            final int first = pair[0];
            final int second = pair[1];
            if (first == second || first < 0 || second < 0 || first >= vertices.size() || second >= vertices.size()) {
                continue;
            }
            final Vertex a = vertices.get(first);
            final Vertex b = vertices.get(second);
            if (a.getDistanceSq(b) <= 0.0) {
                continue; // coincident endpoints: the segment has no direction
            }
            out.add(constraint(mesh.pts, vertices, byU, byV, first, second));
        }
        return out;
    }

    /**
     * Constraint for the segment between two sites, split at every site that lies on it. Tinfour
     * encodes one segment at a time and leaves a zero-area flap when a site sits on the segment's
     * line, so naming the chain up front keeps the boundary a real point-line instead.
     */
    private static IConstraint constraint(
            final List<Point2> pts,
            final List<Vertex> vertices,
            final int[] byU,
            final int[] byV,
            final int first,
            final int second) {
        final List<OnSegment> hits = onSegmentSites(pts, byU, byV, first, second);
        if (hits.isEmpty()) {
            return new LinearConstraint(vertices.get(first), vertices.get(second));
        }
        final List<Vertex> chain = new ArrayList<>(hits.size() + SEGMENT_ENDPOINT_COUNT);
        chain.add(vertices.get(first));
        for (final OnSegment hit : hits) {
            chain.add(vertices.get(hit.site()));
        }
        chain.add(vertices.get(second));
        return new LinearConstraint(chain);
    }

    /**
     * Sites on the open segment between two sites, in order from the first to the second. The scan
     * walks the sites sorted along the segment's longer axis, so a short segment only visits its
     * own extent.
     */
    private static List<OnSegment> onSegmentSites(
            final List<Point2> pts, final int[] byU, final int[] byV, final int first, final int second) {
        final Point2 a = pts.get(first);
        final Point2 b = pts.get(second);
        final double du = b.x() - a.x();
        final double dv = b.y() - a.y();
        final double length = Math.hypot(du, dv);
        if (length <= 0.0) {
            return List.of();
        }
        final boolean alongU = Math.abs(du) >= Math.abs(dv);
        final double low = alongU ? Math.min(a.x(), b.x()) : Math.min(a.y(), b.y());
        final double high = alongU ? Math.max(a.x(), b.x()) : Math.max(a.y(), b.y());
        final List<OnSegment> hits = new ArrayList<>();
        for (final int site : alongU ? byU : byV) {
            if (site == first || site == second) {
                continue;
            }
            final Point2 p = pts.get(site);
            final double position = alongU ? p.x() : p.y();
            if (position < low) {
                continue;
            }
            if (position > high) {
                break;
            }
            final double t = ((p.x() - a.x()) * du + (p.y() - a.y()) * dv) / (length * length);
            if (t * length <= ON_EPS || (1.0 - t) * length <= ON_EPS) {
                continue; // the site coincides with an endpoint
            }
            final double offset = Math.abs((p.x() - a.x()) * dv - (p.y() - a.y()) * du) / length;
            if (offset <= ON_EPS) {
                hits.add(new OnSegment(site, t));
            }
        }
        hits.sort((left, right) -> Double.compare(left.t(), right.t()));
        return hits;
    }

    /** Site indices ordered by one coordinate, so a constraint scan can bound its own extent. */
    private static int[] sitesSortedBy(final List<Point2> pts, final boolean byU) {
        final List<Integer> order = new ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            order.add(i);
        }
        order.sort((left, right) -> Double.compare(
                byU ? pts.get(left).x() : pts.get(left).y(),
                byU ? pts.get(right).x() : pts.get(right).y()));
        final int[] sorted = new int[order.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = order.get(i);
        }
        return sorted;
    }

    /**
     * Copies every non-ghost triangle into the mesh and records the constrained sides. A ghost
     * triangle carries a null vertex: it is the imaginary hull fan, not mesh area.
     */
    private static void readTriangles(final Mesh mesh, final IncrementalTin tin) {
        for (final SimpleTriangle triangle : tin.triangles()) {
            if (triangle.isGhost()) {
                continue;
            }
            final IQuadEdge[] sides = {triangle.getEdgeA(), triangle.getEdgeB(), triangle.getEdgeC()};
            final int[] sites = new int[sides.length];
            for (int i = 0; i < sides.length; i++) {
                sites[i] = mesh.siteIndex(sides[i].getA());
            }
            for (int i = 0; i < sides.length; i++) {
                if (sides[i].isConstrained()) {
                    mesh.constrained.add(undirected(sites[i], sites[(i + 1) % sides.length]));
                }
            }
            mesh.tris.add(oriented(mesh.pts, sites[0], sites[1], sites[2]));
        }
    }

    /**
     * Removes numerically collapsed triangles. Collinear sites make the Delaunay criterion prefer a
     * sliver whose three sites sit on one line; the sliver has no orientation, so it cannot be
     * published as mesh area. Two repairs apply, in order:
     *
     * <ul>
     *   <li>Re-triangulate the sliver with the neighbour across its long edge. This plants the
     *       sliver's middle vertex in the neighbour, keeps both sliver sides as real edges and
     *       drops only the overlapping long edge.
     *   <li>Drop the sliver when the first repair does not apply — the long edge lies on the hull,
     *       for example. A dropped sliver removes no area, so only a constrained side losing its
     *       last carrier blocks the drop.
     * </ul>
     *
     * <p>A constrained long edge is never touched, and a repair that would produce another sliver
     * is refused.</p>
     */
    private static void repairCollapsedTriangles(final Mesh mesh) {
        final Map<Long, List<Integer>> carriers = edgeCarriers(mesh.tris);
        final Set<Integer> dropped = new LinkedHashSet<>();
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] sliver = mesh.tris.get(i);
            final int[] longEdge = collapsedLongEdge(mesh.pts, sliver);
            if (longEdge == null) {
                continue;
            }
            if (replantMiddle(mesh, carriers, dropped, i, sliver, longEdge)) {
                continue;
            }
            if (canDrop(mesh, carriers, dropped, i, sliver)) {
                dropped.add(i);
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        final int[] cursor = {0};
        mesh.tris.removeIf(t -> dropped.contains(cursor[0]++));
    }

    /**
     * Replaces the sliver and its long-edge neighbour with two triangles fanned from the sliver's
     * middle vertex. Reports whether the mesh changed.
     */
    private static boolean replantMiddle(
            final Mesh mesh,
            final Map<Long, List<Integer>> carriers,
            final Set<Integer> dropped,
            final int index,
            final int[] sliver,
            final int[] longEdge) {
        final long key = undirected(longEdge[0], longEdge[1]);
        if (mesh.constrained.contains(key)) {
            return false;
        }
        final Integer partner = survivingCarrier(carriers.get(key), index, dropped);
        if (partner == null) {
            return false;
        }
        final int[] neighbour = mesh.tris.get(partner);
        final int middle = thirdSite(sliver, longEdge[0], longEdge[1]);
        final int apex = thirdSite(neighbour, longEdge[0], longEdge[1]);
        if (middle < 0 || apex < 0 || !liesBetween(mesh.pts, longEdge[0], middle, longEdge[1])) {
            return false;
        }
        final int[] first = oriented(mesh.pts, longEdge[0], middle, apex);
        final int[] second = oriented(mesh.pts, middle, longEdge[1], apex);
        if (collapsedLongEdge(mesh.pts, first) != null || collapsedLongEdge(mesh.pts, second) != null) {
            return false;
        }
        unlinkEdges(carriers, index, sliver);
        unlinkEdges(carriers, partner, neighbour);
        mesh.tris.set(index, first);
        mesh.tris.set(partner, second);
        linkEdges(carriers, index, first);
        linkEdges(carriers, partner, second);
        return true;
    }

    /**
     * Whether the sliver may leave the mesh without breaking a constraint. An unconstrained side
     * may disappear with the sliver: it carries no area and no caller contract. A constrained side
     * may disappear only when the sliver's middle vertex still splits it into a collinear chain of
     * surviving edges, which is the same guarantee a constraint subdivided at a site keeps.
     */
    private static boolean canDrop(
            final Mesh mesh,
            final Map<Long, List<Integer>> carriers,
            final Set<Integer> dropped,
            final int index,
            final int[] sliver) {
        boolean chainPromoted = false;
        final List<Long> retired = new ArrayList<>(TRIANGLE_VERTEX_COUNT);
        final List<Long> promoted = new ArrayList<>(TRIANGLE_VERTEX_COUNT * TRIANGLE_VERTEX_COUNT);
        for (int e = 0; e < TRIANGLE_VERTEX_COUNT; e++) {
            final int u = sliver[e];
            final int v = sliver[(e + 1) % TRIANGLE_VERTEX_COUNT];
            final long key = undirected(u, v);
            if (!mesh.constrained.contains(key) || survivingCarrier(carriers.get(key), index, dropped) != null) {
                continue;
            }
            final int middle = thirdSite(sliver, u, v);
            if (middle < 0
                    || !liesBetween(mesh.pts, u, middle, v)
                    || survivingCarrier(carriers.get(undirected(u, middle)), index, dropped) == null
                    || survivingCarrier(carriers.get(undirected(middle, v)), index, dropped) == null) {
                return false;
            }
            retired.add(key);
            promoted.add(undirected(u, middle));
            promoted.add(undirected(middle, v));
            chainPromoted = true;
        }
        if (!chainPromoted) {
            return true;
        }
        // The published ring edge becomes the chain that replaces it, so the domain and mask
        // filters keep treating the boundary as load-bearing.
        mesh.constrained.removeAll(retired);
        mesh.constrained.addAll(promoted);
        return true;
    }

    /**
     * The long edge of a collapsed triangle, or {@code null} when the triangle carries usable
     * area.
     */
    private static int[] collapsedLongEdge(final List<Point2> pts, final int[] triangle) {
        int best = -1;
        double bestLengthSq = -1.0;
        for (int e = 0; e < TRIANGLE_VERTEX_COUNT; e++) {
            final int u = triangle[e];
            final int v = triangle[(e + 1) % TRIANGLE_VERTEX_COUNT];
            final double lengthSq = lengthSquared(pts, u, v);
            if (lengthSq > bestLengthSq) {
                bestLengthSq = lengthSq;
                best = e;
            }
        }
        if (best < 0 || bestLengthSq <= 0.0) {
            return null;
        }
        final int u = triangle[best];
        final int v = triangle[(best + 1) % TRIANGLE_VERTEX_COUNT];
        final double doubledArea =
                Math.abs(orient(pts.get(u), pts.get(v), pts.get(triangle[(best + 2) % TRIANGLE_VERTEX_COUNT])));
        return doubledArea / Math.sqrt(bestLengthSq) <= COLLAPSED_HEIGHT ? new int[] {u, v} : null;
    }

    private static double lengthSquared(final List<Point2> pts, final int u, final int v) {
        final double du = pts.get(v).x() - pts.get(u).x();
        final double dv = pts.get(v).y() - pts.get(u).y();
        return du * du + dv * dv;
    }

    /** The vertex of {@code triangle} that is neither {@code u} nor {@code v}. */
    private static int thirdSite(final int[] triangle, final int u, final int v) {
        for (final int site : triangle) {
            if (site != u && site != v) {
                return site;
            }
        }
        return -1;
    }

    /** Whether the projection of {@code site} lands strictly inside the open segment u-v. */
    private static boolean liesBetween(final List<Point2> pts, final int u, final int site, final int v) {
        final double lengthSq = lengthSquared(pts, u, v);
        if (lengthSq <= 0.0) {
            return false;
        }
        final double ex = pts.get(v).x() - pts.get(u).x();
        final double ey = pts.get(v).y() - pts.get(u).y();
        final double t = ((pts.get(site).x() - pts.get(u).x()) * ex
                        + (pts.get(site).y() - pts.get(u).y()) * ey)
                / lengthSq;
        return t > 0.0 && t < 1.0;
    }

    /** Undirected edge to the triangle indices that carry it. */
    private static Map<Long, List<Integer>> edgeCarriers(final List<int[]> triangles) {
        final Map<Long, List<Integer>> carriers = new HashMap<>();
        for (int i = 0; i < triangles.size(); i++) {
            linkEdges(carriers, i, triangles.get(i));
        }
        return carriers;
    }

    private static void linkEdges(final Map<Long, List<Integer>> carriers, final int triangle, final int[] sites) {
        for (int e = 0; e < TRIANGLE_VERTEX_COUNT; e++) {
            carriers.computeIfAbsent(
                            undirected(sites[e], sites[(e + 1) % TRIANGLE_VERTEX_COUNT]), k -> new ArrayList<>())
                    .add(triangle);
        }
    }

    private static void unlinkEdges(final Map<Long, List<Integer>> carriers, final int triangle, final int[] sites) {
        for (int e = 0; e < TRIANGLE_VERTEX_COUNT; e++) {
            final List<Integer> owners = carriers.get(undirected(sites[e], sites[(e + 1) % TRIANGLE_VERTEX_COUNT]));
            if (owners != null) {
                owners.remove(Integer.valueOf(triangle));
            }
        }
    }

    /**
     * The carrier other than {@code self} that is still in the mesh, or {@code null} when the edge
     * has no second owner.
     */
    private static Integer survivingCarrier(final List<Integer> owners, final int self, final Set<Integer> dropped) {
        if (owners == null) {
            return null;
        }
        for (final Integer owner : owners) {
            if (owner.intValue() != self && !dropped.contains(owner)) {
                return owner;
            }
        }
        return null;
    }

    /**
     * Drops every triangle whose centroid is outside the domain carved by {@code rings}:
     * silhouette rings keep their interior, hole rings exclude theirs. Ring role is decided by
     * nesting depth — a ring inside an odd number of other rings is a hole — so the test is
     * winding-agnostic. A centroid grazing a ring edge within {@link #ON_EPS} counts as inside:
     * the edge belongs to the material and slivers hugging it must not strand wedges.
     */
    private static void classifyDomain(final Mesh mesh, final List<int[]> rings) {
        if (rings == null || rings.isEmpty()) {
            return;
        }
        final List<int[]> outer = new ArrayList<>();
        final List<int[]> holes = new ArrayList<>();
        final List<int[]> usable = new ArrayList<>();
        for (final int[] ring : rings) {
            if (ring != null
                    && ring.length >= TRIANGLE_VERTEX_COUNT
                    && Math.abs(ringArea(mesh.pts, ring)) > MIN_RING_AREA) {
                usable.add(ring);
            }
        }
        for (int i = 0; i < usable.size(); i++) {
            int depth = 0;
            for (int j = 0; j < usable.size(); j++) {
                if (i != j && ringContainsRing(mesh.pts, usable.get(j), usable.get(i))) {
                    depth++;
                }
            }
            (depth % 2 == 0 ? outer : holes).add(usable.get(i));
        }
        if (outer.isEmpty()) {
            return;
        }
        // First mark every out-of-domain triangle as a drop candidate; then rescue a carrier per
        // constrained edge whose triangles would all be dropped — constrained edges are
        // load-bearing and a subdivided chain may dip outside the ring while still needing one
        // triangle to keep the chain alive.
        final Set<Integer> drop = new HashSet<>();
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            final Centroid centroid = centroid(mesh.pts, t);
            if (!insideAnyRing(mesh.pts, outer, centroid.u(), centroid.v())
                    || insideAnyRingStrict(mesh.pts, holes, centroid.u(), centroid.v())) {
                drop.add(i);
            }
        }
        // edge -> carrier indices
        final Map<Long, List<Integer>> carriers = edgeCarriers(mesh.tris);
        final Set<Integer> rescued = new HashSet<>();
        for (final long edge : mesh.constrained) {
            final List<Integer> tris = carriers.get(edge);
            if (tris == null) {
                continue;
            }
            boolean hasSurvivor = false;
            for (final int ti : tris) {
                if (!drop.contains(ti)) {
                    hasSurvivor = true;
                    break;
                }
            }
            if (!hasSurvivor) {
                rescued.add(tris.get(0));
            }
        }
        drop.removeAll(rescued);
        if (!drop.isEmpty()) {
            final int[] cursor = {0};
            mesh.tris.removeIf(t -> drop.contains(cursor[0]++));
        }
    }

    /**
     * Drops every triangle whose centroid the mask rejects. A triangle that carries a constrained
     * edge survives, so the ring and separator edges the caller asked for stay in the output.
     */
    private static void applyMask(final Mesh mesh, final DomainMask mask) {
        mesh.tris.removeIf(t -> !carriesConstraint(mesh, t) && !insideOrNear(mask, centroid(mesh.pts, t)));
    }

    private static boolean carriesConstraint(final Mesh mesh, final int[] triangle) {
        for (int e = 0; e < TRIANGLE_VERTEX_COUNT; e++) {
            if (mesh.constrained.contains(undirected(triangle[e], triangle[(e + 1) % TRIANGLE_VERTEX_COUNT]))) {
                return true;
            }
        }
        return false;
    }

    /** Vertex average of one triangle, the sample point both domain tests classify. */
    private static Centroid centroid(final List<Point2> pts, final int[] triangle) {
        final Point2 a = pts.get(triangle[0]);
        final Point2 b = pts.get(triangle[1]);
        final Point2 c = pts.get(triangle[2]);
        return new Centroid(
                (a.x() + b.x() + c.x()) / VERTEX_AVERAGE_DIVISOR, (a.y() + b.y() + c.y()) / VERTEX_AVERAGE_DIVISOR);
    }

    /**
     * Whether every vertex of {@code inner} lies inside (or on the boundary of) {@code ring}.
     * Ring vertices can coincide across loops, so a midpoint of the first edge decides.
     */
    private static boolean ringContainsRing(final List<Point2> pts, final int[] ring, final int[] inner) {
        final Point2 a = pts.get(inner[0]);
        final Point2 b = pts.get(inner[1 % inner.length]);
        final double mu = (a.x() + b.x()) / 2.0;
        final double mv = (a.y() + b.y()) / 2.0;
        return pointInOrOnRing(pts, ring, mu, mv);
    }

    /** Signed shoelace area of a closed vertex-index ring. */
    private static double ringArea(final List<Point2> pts, final int[] ring) {
        double area = 0.0;
        for (int i = 0; i < ring.length; i++) {
            final Point2 p = pts.get(ring[i]);
            final Point2 q = pts.get(ring[(i + 1) % ring.length]);
            area += p.x() * q.y() - q.x() * p.y();
        }
        return area / 2.0;
    }

    /** Strictly inside or grazing any of {@code rings}. */
    private static boolean insideAnyRing(
            final List<Point2> pts, final List<int[]> rings, final double u, final double v) {
        for (final int[] ring : rings) {
            if (pointInOrOnRing(pts, ring, u, v)) {
                return true;
            }
        }
        return false;
    }

    /** Strictly inside any of {@code rings} (hole test: grazing an edge is not inside). */
    private static boolean insideAnyRingStrict(
            final List<Point2> pts, final List<int[]> rings, final double u, final double v) {
        for (final int[] ring : rings) {
            if (pointInRing(pts, ring, u, v) && !nearRing(pts, ring, u, v)) {
                return true;
            }
        }
        return false;
    }

    /** Even-odd point-in-polygon, or a graze of any ring edge within {@link #ON_EPS}. */
    private static boolean pointInOrOnRing(final List<Point2> pts, final int[] ring, final double u, final double v) {
        return pointInRing(pts, ring, u, v) || nearRing(pts, ring, u, v);
    }

    /** Even-odd ray-cast containment (no boundary tolerance). */
    private static boolean pointInRing(final List<Point2> pts, final int[] ring, final double u, final double v) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final Point2 a = pts.get(ring[i]);
            final Point2 b = pts.get(ring[j]);
            if ((a.y() > v) != (b.y() > v) && u < (b.x() - a.x()) * (v - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Whether {@code (u,v)} sits within {@link #ON_EPS} of any ring edge. */
    private static boolean nearRing(final List<Point2> pts, final int[] ring, final double u, final double v) {
        for (int i = 0; i < ring.length; i++) {
            final Point2 a = pts.get(ring[i]);
            final Point2 b = pts.get(ring[(i + 1) % ring.length]);
            final double ex = b.x() - a.x();
            final double ey = b.y() - a.y();
            final double lenSq = ex * ex + ey * ey;
            if (lenSq < COLLAPSED_LENGTH_SQ) {
                if (Math.hypot(u - a.x(), v - a.y()) < ON_EPS) {
                    return true;
                }
                continue;
            }
            final double t = Math.max(0.0, Math.min(1.0, ((u - a.x()) * ex + (v - a.y()) * ey) / lenSq));
            final double dx = u - (a.x() + t * ex);
            final double dy = v - (a.y() + t * ey);
            if (dx * dx + dy * dy < ON_EPS * ON_EPS) {
                return true;
            }
        }
        return false;
    }

    /** Whether the raster domain keeps the cell under the centroid or a cell next to it. */
    private static boolean insideOrNear(final DomainMask mask, final Centroid centroid) {
        final int cell = mask.cellAtUv(centroid.u(), centroid.v());
        if (cell < 0) {
            return false;
        }
        if (mask.solidCell(cell)) {
            return true;
        }
        final int w = mask.width();
        final int x = cell % w;
        final int y = cell / w;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int nx = x + dx;
                final int ny = y + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < mask.height() && mask.solidCell(ny * w + nx)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Axis-aligned UV extent of a site set. */
    private record Bounds(double minU, double minV, double maxU, double maxV) {

        /** Returns the extent of {@code pts}. Requires at least one point. */
        static Bounds of(final List<Point2> pts) {
            final Point2 seed = pts.get(0);
            double minU = seed.x();
            double maxU = seed.x();
            double minV = seed.y();
            double maxV = seed.y();
            for (int i = 1; i < pts.size(); i++) {
                final Point2 p = pts.get(i);
                minU = Math.min(minU, p.x());
                maxU = Math.max(maxU, p.x());
                minV = Math.min(minV, p.y());
                maxV = Math.max(maxV, p.y());
            }
            return new Bounds(minU, minV, maxU, maxV);
        }

        /**
         * Mean site spacing: the square root of the extent area shared out over the site count.
         * Tinfour scales its numeric tolerances by this value, so the estimate must sit near the
         * real neighbour distance instead of a fixed constant.
         */
        double nominalSpacing(final int siteCount) {
            return Math.sqrt((maxU - minU) * (maxV - minV) / siteCount);
        }
    }

    /** Sample point of one triangle. */
    private record Centroid(double u, double v) {}

    /** A site that lies on a requested constraint segment, with its position along the segment. */
    private record OnSegment(int site, double t) {}

    /** Working triangulation, the constrained-edge ledger and the vertex-to-site mapping. */
    private static final class Mesh {
        final List<Point2> pts;
        final List<int[]> tris = new ArrayList<>();
        final Set<Long> constrained = new LinkedHashSet<>();
        final Map<Vertex, Integer> vertexToSite = new IdentityHashMap<>();
        final int inputCount;

        Mesh(final List<Point2> points) {
            this.pts = new ArrayList<>(points);
            this.inputCount = points.size();
        }

        /**
         * Site index of one Tinfour vertex. A merged coincident site resolves to its first member,
         * and a vertex without a matching site adds one at its own location, so an output index
         * always names a real point.
         */
        int siteIndex(final Vertex vertex) {
            final Integer known = vertexToSite.get(vertex);
            if (known != null) {
                return known;
            }
            final int reported = vertex.getIndex();
            final int site;
            if (!vertex.isSynthetic() && reportsSite(vertex, reported)) {
                site = reported;
            } else {
                site = pts.size();
                pts.add(new Point2((float) vertex.getX(), (float) vertex.getY()));
            }
            vertexToSite.put(vertex, site);
            return site;
        }

        /** Whether the reported index names the site this vertex occupies. */
        private boolean reportsSite(final Vertex vertex, final int reported) {
            return reported >= 0
                    && reported < inputCount
                    && pts.get(reported).x() == (float) vertex.getX()
                    && pts.get(reported).y() == (float) vertex.getY();
        }

        List<Integer> flattenTriangles() {
            final List<Integer> out = new ArrayList<>(tris.size() * TRIANGLE_VERTEX_COUNT);
            for (final int[] t : tris) {
                for (final int site : t) {
                    out.add(site);
                }
            }
            return out;
        }
    }

    /** Rewinds a triangle into counter-clockwise order. */
    private static int[] oriented(final List<Point2> pts, final int a, final int b, final int c) {
        if (orient(pts.get(a), pts.get(b), pts.get(c)) < 0) {
            return new int[] {a, c, b};
        }
        return new int[] {a, b, c};
    }

    private static double orient(final Point2 a, final Point2 b, final Point2 c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static long undirected(final int a, final int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
    }
}
