package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constrained Delaunay triangulation in UV space.
 *
 * <p>Incremental Bowyer–Watson pass over all points first; then each constraint segment is
 * inserted with the classic cavity algorithm: every triangle crossed by the segment is removed
 * and the two pseudo-polygons left behind are re-triangulated independently, which makes the
 * segment a triangulation edge by construction. Established constraints are never touched: a
 * segment that pierces an existing constraint is split at a Steiner point planted on that edge,
 * and a segment running through existing vertices is split into a collinear chain. A final
 * Lawson pass restores near-Delaunay quality without ever flipping a constrained edge, and the
 * constrained-edge ledger is reconciled against the real triangle set before filtering.</p>
 */
public final class ConstraintDelaunay {

    /** Distance (UV units) below which a point counts as lying on a segment or vertex. */
    private static final double ON_EPS = 1.0e-6;

    private ConstraintDelaunay() {}

    /**
     * @param points sites surviving in the output plus any Steiner points appended
     * @param triangleIndices flat index triples into {@code points}
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
        if (mesh.pts.size() < 3) {
            return new Result(List.copyOf(mesh.pts), List.of());
        }
        mesh.superBase = addSuperTriangle(mesh.pts, mesh.tris);
        for (int i = 0; i < points.size(); i++) {
            insertPoint(mesh.pts, mesh.tris, i);
        }
        // Super-triangle triangles stay alive through constraint enforcement: a finite
        // super-triangle never guarantees hull coverage after its triangles are deleted, and
        // hull-adjacent constraints would otherwise walk through uncovered air.

        for (final int[] c : constraints) {
            if (c[0] == c[1]
                    || c[0] >= mesh.pts.size()
                    || c[1] >= mesh.pts.size()
                    || mesh.isSuper(c[0])
                    || mesh.isSuper(c[1])) {
                continue;
            }
            enforceConstraint(mesh, c[0], c[1]);
        }
        // Constraints satisfied by a collinear subdivided chain carry real triangle edges that
        // were never marked: sweep once and promote every on-segment edge of every constraint,
        // so the ledger covers whole chains and filtering keeps their carriers.
        markCollinearChains(mesh, constraints);
        // The ledger must mirror reality: a constrained edge that is not a real triangle edge
        // would silently disable domain filtering around the silhouette it protects.
        mesh.constrained.removeIf(k -> !edgeExists(mesh.tris, (int) (k >>> 32), (int) (k & 0xFFFF_FFFFL)));
        restoreDelaunay(mesh);
        mesh.tris.removeIf(t -> mesh.isSuper(t[0]) || mesh.isSuper(t[1]) || mesh.isSuper(t[2]));

        // Domain classification: ring constraints make the silhouette edges part of the
        // triangulation, so material membership reduces to a point-in-polygon test on the
        // triangle centroid — inside an outer ring and outside every hole ring.
        classifyDomain(mesh, domainRings);

        if (mask != null) {
            mesh.tris.removeIf(t -> {
                // Constraint-carrying triangles are load-bearing: dropping one would sever
                // a ring edge or separator and reopen the silhouette the caller guaranteed.
                if (mesh.constrained.contains(undirected(t[0], t[1]))
                        || mesh.constrained.contains(undirected(t[1], t[2]))
                        || mesh.constrained.contains(undirected(t[2], t[0]))) {
                    return false;
                }
                final Point2 a = mesh.pts.get(t[0]);
                final Point2 b = mesh.pts.get(t[1]);
                final Point2 c = mesh.pts.get(t[2]);
                final double cu = (a.x() + b.x() + c.x()) / 3.0;
                final double cv = (a.y() + b.y() + c.y()) / 3.0;
                return !insideOrNear(mask, cu, cv);
            });
        }
        // Compact the point list: super-triangle vertices occupy [superBase, superBase + 3).
        final int[] remap = new int[mesh.pts.size()];
        final List<Point2> outPts = new ArrayList<>(mesh.pts.size() - 3);
        for (int i = 0; i < mesh.pts.size(); i++) {
            if (i >= mesh.superBase && i < mesh.superBase + 3) {
                remap[i] = -1;
            } else {
                remap[i] = outPts.size();
                outPts.add(mesh.pts.get(i));
            }
        }
        final List<Integer> out = new ArrayList<>(mesh.tris.size() * 3);
        for (final int[] t : mesh.tris) {
            out.add(remap[t[0]]);
            out.add(remap[t[1]]);
            out.add(remap[t[2]]);
        }
        return new Result(List.copyOf(outPts), out);
    }

    /**
     * Drops every triangle whose centroid is outside the domain carved by {@code rings}:
     * silhouette rings keep their interior, hole rings exclude theirs. Ring role is decided
     * by nesting depth — a ring inside an odd number of other rings is a hole — so the test
     * is winding-agnostic. A centroid grazing a ring edge within {@link #ON_EPS} counts as
     * inside: the edge belongs to the material and slivers hugging it must not strand wedges.
     */
    private static void classifyDomain(final Mesh mesh, final List<int[]> rings) {
        if (rings == null || rings.isEmpty()) {
            return;
        }
        final List<int[]> outer = new ArrayList<>();
        final List<int[]> holes = new ArrayList<>();
        final List<int[]> usable = new ArrayList<>();
        for (final int[] ring : rings) {
            if (ring != null && ring.length >= 3 && Math.abs(ringArea(mesh.pts, ring)) > 1.0e-12) {
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
        // First mark every out-of-domain triangle as a drop candidate; then rescue a carrier
        // per constrained edge whose triangles would all be dropped — constrained edges are
        // load-bearing and a subdivided chain may dip outside the ring while still needing
        // one triangle to keep the chain alive.
        final Set<Integer> drop = new HashSet<>();
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            final Point2 a = mesh.pts.get(t[0]);
            final Point2 b = mesh.pts.get(t[1]);
            final Point2 c = mesh.pts.get(t[2]);
            final double cu = (a.x() + b.x() + c.x()) / 3.0;
            final double cv = (a.y() + b.y() + c.y()) / 3.0;
            if (!insideAnyRing(mesh.pts, outer, cu, cv) || insideAnyRingStrict(mesh.pts, holes, cu, cv)) {
                drop.add(i);
            }
        }
        // edge -> carrier indices
        final Map<Long, List<Integer>> carriers = new HashMap<>();
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            for (int e = 0; e < 3; e++) {
                carriers.computeIfAbsent(undirected(t[e], t[(e + 1) % 3]), k -> new ArrayList<>())
                        .add(i);
            }
        }
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
            if (lenSq < 1.0e-24) {
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

    private static boolean insideOrNear(final DomainMask mask, final double u, final double v) {
        final int cell = mask.cellAtUv(u, v);
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

    /** Working triangulation plus the constrained-edge ledger. */
    private static final class Mesh {
        final List<Point2> pts;
        final List<int[]> tris = new ArrayList<>();
        final Set<Long> constrained = new LinkedHashSet<>();
        int superBase = -1;

        Mesh(final List<Point2> points) {
            this.pts = new ArrayList<>(points);
        }

        boolean isSuper(final int v) {
            return v >= superBase && v < superBase + 3;
        }
    }

    // --- Bowyer–Watson ------------------------------------------------------

    private static int addSuperTriangle(final List<Point2> pts, final List<int[]> tris) {
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (final Point2 p : pts) {
            minU = Math.min(minU, p.x());
            maxU = Math.max(maxU, p.x());
            minV = Math.min(minV, p.y());
            maxV = Math.max(maxV, p.y());
        }
        final double cx = (minU + maxU) / 2;
        final double cy = (minV + maxV) / 2;
        final double d = Math.max(maxU - minU, maxV - minV) + 1.0e-3;
        final int base = pts.size();
        pts.add(new Point2((float) (cx - 8 * d), (float) (cy - 6 * d)));
        pts.add(new Point2((float) (cx + 8 * d), (float) (cy - 6 * d)));
        pts.add(new Point2((float) cx, (float) (cy + 8 * d)));
        tris.add(new int[] {base, base + 1, base + 2});
        return base;
    }

    /**
     * Removes the cavity whose circumcircles contain point {@code i} and re-triangulates it.
     * With nearly degenerate inputs (collinear runs, cocircular clusters) the circumcircle
     * cavity can turn out non-star-shaped — the point cannot see every boundary edge — and
     * re-triangulating it would fold triangles over the rim, punching a hole in coverage.
     * Detected before mutating, it falls back to a plain containing-triangle/edge split.
     */
    private static void insertPoint(final List<Point2> pts, final List<int[]> tris, final int i) {
        final Point2 p = pts.get(i);
        final List<Integer> cavity = new ArrayList<>();
        final Map<Long, int[]> edges = new HashMap<>();
        final Map<Long, Integer> counts = new HashMap<>();
        for (int t = 0; t < tris.size(); t++) {
            final int[] tri = tris.get(t);
            if (!circumcircleContains(pts, tri, p)) {
                continue;
            }
            cavity.add(t);
            for (int e = 0; e < 3; e++) {
                final int a = tri[e];
                final int b = tri[(e + 1) % 3];
                final long key = undirected(a, b);
                edges.putIfAbsent(key, new int[] {a, b});
                counts.merge(key, 1, Integer::sum);
            }
        }
        // Star-shape check: the cavity interior lies left of every boundary edge, so the new
        // point must see each edge with positive orientation or the fill leaves a notch.
        boolean star = !cavity.isEmpty();
        for (final Map.Entry<Long, int[]> entry : edges.entrySet()) {
            if (counts.get(entry.getKey()) != 1) {
                continue;
            }
            final int[] ab = entry.getValue();
            if (orient(pts.get(ab[0]), pts.get(ab[1]), p) <= 0.0) {
                star = false;
                break;
            }
        }
        if (!star) {
            insertPointBySplit(pts, tris, i);
            return;
        }
        final Set<Integer> dead = new HashSet<>(cavity);
        final int[] cursor = {0};
        tris.removeIf(t -> dead.contains(cursor[0]++));
        for (final Map.Entry<Long, int[]> entry : edges.entrySet()) {
            if (counts.get(entry.getKey()) != 1) {
                continue;
            }
            final int[] ab = entry.getValue();
            tris.add(oriented(pts, ab[0], ab[1], i));
        }
    }

    /**
     * Inserts already-listed vertex {@code w} by splitting the triangle or edge containing it —
     * O(n) location scan, always valid, used when the circumcircle cavity is unreliable.
     */
    private static void insertPointBySplit(final List<Point2> pts, final List<int[]> tris, final int w) {
        final Point2 p = pts.get(w);
        for (int i = 0; i < tris.size(); i++) {
            final int[] t = tris.get(i);
            final Point2 p0 = pts.get(t[0]);
            final Point2 p1 = pts.get(t[1]);
            final Point2 p2 = pts.get(t[2]);
            final double area = orient(p0, p1, p2);
            final double tol = Math.abs(area) * 1.0e-9 + 1.0e-20;
            if (orient(p0, p1, p) < -tol || orient(p1, p2, p) < -tol || orient(p2, p0, p) < -tol) {
                continue;
            }
            for (final int v : t) {
                final Point2 pv = pts.get(v);
                if (Math.hypot(pv.x() - p.x(), pv.y() - p.y()) < ON_EPS) {
                    return; // coincides with an existing vertex — already inserted
                }
            }
            for (int e = 0; e < 3; e++) {
                final int x = t[e];
                final int y = t[(e + 1) % 3];
                final Point2 px = pts.get(x);
                final Point2 py = pts.get(y);
                final double ex = py.x() - px.x();
                final double ey = py.y() - px.y();
                final double elen = Math.hypot(ex, ey);
                if (elen < 1.0e-12) {
                    continue;
                }
                final double perp = Math.abs((p.x() - px.x()) * ey - (p.y() - px.y()) * ex) / elen;
                final double tp = ((p.x() - px.x()) * ex + (p.y() - px.y()) * ey) / (elen * elen);
                if (perp >= ON_EPS || tp <= 0.0 || tp >= 1.0) {
                    continue;
                }
                // Point on edge (x,y): split it in the one or two adjacent triangles.
                final List<Integer> adjacent = new ArrayList<>(2);
                final List<Integer> apexes = new ArrayList<>(2);
                final long key = undirected(x, y);
                for (int j = 0; j < tris.size(); j++) {
                    final int[] q = tris.get(j);
                    for (int f = 0; f < 3; f++) {
                        if (undirected(q[f], q[(f + 1) % 3]) == key) {
                            adjacent.add(j);
                            apexes.add(q[(f + 2) % 3]);
                            break;
                        }
                    }
                }
                for (int k = adjacent.size() - 1; k >= 0; k--) {
                    tris.remove((int) adjacent.get(k));
                }
                for (final int apex : apexes) {
                    tris.add(oriented(pts, x, w, apex));
                    tris.add(oriented(pts, w, y, apex));
                }
                return;
            }
            tris.remove(i);
            tris.add(oriented(pts, t[0], t[1], w));
            tris.add(oriented(pts, t[1], t[2], w));
            tris.add(oriented(pts, t[2], t[0], w));
            return;
        }
        // Not covered by any triangle: the point stays isolated until constraints use it.
    }

    /** Strict circumcircle containment (CCW triangle), with a small inward tolerance. */
    private static boolean circumcircleContains(final List<Point2> pts, final int[] tri, final Point2 p) {
        final Point2 a = pts.get(tri[0]);
        final Point2 b = pts.get(tri[1]);
        final Point2 c = pts.get(tri[2]);
        final double ax = a.x() - p.x();
        final double ay = a.y() - p.y();
        final double bx = b.x() - p.x();
        final double by = b.y() - p.y();
        final double cx = c.x() - p.x();
        final double cy = c.y() - p.y();
        final double det = (ax * ax + ay * ay) * (bx * cy - cx * by)
                - (bx * bx + by * by) * (ax * cy - cx * ay)
                + (cx * cx + cy * cy) * (ax * by - bx * ay);
        return det > -1.0e-14;
    }

    private static int[] oriented(final List<Point2> pts, final int a, final int b, final int c) {
        if (orient(pts.get(a), pts.get(b), pts.get(c)) < 0) {
            return new int[] {a, c, b};
        }
        return new int[] {a, b, c};
    }

    // --- Constraint enforcement ---------------------------------------------
    //
    // Invariants: the constrained ledger only ever names edges that exist in the mesh; no
    // operation ever removes a constrained edge. Splits (collinear vertices or crossings)
    // replace a pending segment by shorter pieces processed left to right.

    private static void enforceConstraint(final Mesh mesh, final int a0, final int b0) {
        final ArrayDeque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[] {a0, b0});
        int guard = 64 * mesh.pts.size() + 256;
        while (!pending.isEmpty() && guard-- > 0) {
            final int[] seg = pending.pop();
            final int a = seg[0];
            final int b = seg[1];
            if (a == b) {
                continue;
            }
            if (edgeExists(mesh.tris, a, b)) {
                mesh.constrained.add(undirected(a, b));
                continue;
            }
            // Vertices sitting on the open segment split it into a collinear chain.
            final List<Integer> on = onSegmentVertices(mesh, a, b);
            if (!on.isEmpty()) {
                final List<int[]> chain = new ArrayList<>(on.size() + 1);
                int prev = a;
                for (final int v : on) {
                    chain.add(new int[] {prev, v});
                    prev = v;
                }
                chain.add(new int[] {prev, b});
                for (int i = chain.size() - 1; i >= 0; i--) {
                    pending.push(chain.get(i));
                }
                continue;
            }
            // A segment that pierces an established constraint yields to it: plant the
            // intersection on that edge (splitting it into two constrained halves) and
            // retry the shortened pieces.
            final long crossed = nearestCrossingConstraint(mesh, a, b);
            if (crossed >= 0) {
                final int c = (int) (crossed >>> 32);
                final int d = (int) (crossed & 0xFFFF_FFFFL);
                final int w = splitEdgeAtCrossing(mesh, c, d, a, b);
                if (w >= 0 && w != a && w != b) {
                    pending.push(new int[] {w, b});
                    pending.push(new int[] {a, w});
                    continue;
                }
            }
            final boolean carved = insertConstraint(mesh, a, b);
            if (carved && edgeExists(mesh.tris, a, b)) {
                mesh.constrained.add(undirected(a, b));
                continue;
            }
            // Carve refused (walk failure or an established constraint sitting inside the
            // cavity): flips never delete constrained edges, so try routing the crossing
            // diagonals out of the way first.
            if (flipEnforce(mesh, a, b)) {
                if (edgeExists(mesh.tris, a, b)) {
                    mesh.constrained.add(undirected(a, b));
                }
                continue;
            }
            // Last resort: midpoint Steiner, inserted without ever removing a constrained
            // edge (edge split when the point lands on one, triangle split otherwise).
            final int mid = insertSteiner(mesh, a, b);
            if (mid < 0 || mid == a || mid == b) {
                continue;
            }
            pending.push(new int[] {mid, b});
            pending.push(new int[] {a, mid});
        }
    }

    /**
     * Classic flip convergence for segment {@code a-b}: every unconstrained edge properly
     * crossed by the segment is flipped when its quad is strictly convex and the replacement
     * diagonal does not itself cross the segment. Constrained edges are never flipped, so no
     * established constraint can be lost here. Returns when the edge exists or no flip helps.
     */
    private static boolean flipEnforce(final Mesh mesh, final int a, final int b) {
        for (int pass = 0; pass < 64; pass++) {
            if (edgeExists(mesh.tris, a, b)) {
                return true;
            }
            boolean progressed = false;
            for (int i = 0; i < mesh.tris.size(); i++) {
                final int[] t = mesh.tris.get(i);
                for (int e = 0; e < 3; e++) {
                    if (flipCrossing(mesh, i, e, a, b)) {
                        progressed = true;
                    }
                }
            }
            if (!progressed) {
                return edgeExists(mesh.tris, a, b);
            }
        }
        return edgeExists(mesh.tris, a, b);
    }

    /**
     * Flips edge {@code e} of triangle {@code ti} when it is properly crossed by segment
     * {@code a-b}, not constrained, and the quad is convex with a non-crossing replacement.
     */
    private static boolean flipCrossing(final Mesh mesh, final int ti, final int e, final int a, final int b) {
        final int[] t1 = mesh.tris.get(ti);
        final int x = t1[e];
        final int y = t1[(e + 1) % 3];
        final int p = t1[(e + 2) % 3];
        if (x == a || x == b || y == a || y == b) {
            return false; // shared endpoints cannot properly cross
        }
        final long key = undirected(x, y);
        if (mesh.constrained.contains(key)) {
            return false;
        }
        if (!segmentsCross(mesh.pts.get(a), mesh.pts.get(b), mesh.pts.get(x), mesh.pts.get(y))) {
            return false;
        }
        int tj = -1;
        int q = -1;
        for (int i = 0; i < mesh.tris.size(); i++) {
            if (i == ti) {
                continue;
            }
            final int[] t = mesh.tris.get(i);
            for (int f = 0; f < 3; f++) {
                if (undirected(t[f], t[(f + 1) % 3]) == key) {
                    tj = i;
                    q = t[(f + 2) % 3];
                    break;
                }
            }
            if (tj >= 0) {
                break;
            }
        }
        if (tj < 0 || q == p || q == a || q == b) {
            return false; // boundary edge or degenerate quad
        }
        if (!convexQuad(mesh.pts, p, x, q, y)) {
            return false;
        }
        // The new diagonal (p,q) must not replace one crossing with another.
        if (segmentsCross(mesh.pts.get(a), mesh.pts.get(b), mesh.pts.get(p), mesh.pts.get(q))) {
            return false;
        }
        mesh.tris.set(ti, oriented(mesh.pts, p, x, q));
        mesh.tris.set(tj, oriented(mesh.pts, p, q, y));
        return true;
    }

    /** Vertices strictly between {@code a} and {@code b} and within {@link #ON_EPS} of the line. */
    private static List<Integer> onSegmentVertices(final Mesh mesh, final int a, final int b) {
        final Point2 pa = mesh.pts.get(a);
        final Point2 pb = mesh.pts.get(b);
        final double abx = pb.x() - pa.x();
        final double aby = pb.y() - pa.y();
        final double len = Math.hypot(abx, aby);
        if (len < 1.0e-12) {
            return List.of();
        }
        final List<Integer> ids = new ArrayList<>();
        final List<Double> ts = new ArrayList<>();
        for (int i = 0; i < mesh.pts.size(); i++) {
            if (i == a || i == b || mesh.isSuper(i)) {
                continue;
            }
            final Point2 p = mesh.pts.get(i);
            final double t = ((p.x() - pa.x()) * abx + (p.y() - pa.y()) * aby) / (len * len);
            if (t <= 0.0 || t >= 1.0) {
                continue;
            }
            final double perp = Math.abs((p.x() - pa.x()) * aby - (p.y() - pa.y()) * abx) / len;
            if (perp >= ON_EPS) {
                continue;
            }
            if (t * len < ON_EPS || (1.0 - t) * len < ON_EPS) {
                continue; // coincides with an endpoint
            }
            ids.add(i);
            ts.add(t);
        }
        for (int i = 1; i < ids.size(); i++) {
            for (int j = i; j > 0 && ts.get(j) < ts.get(j - 1); j--) {
                final double tt = ts.get(j);
                ts.set(j, ts.get(j - 1));
                ts.set(j - 1, tt);
                final int ii = ids.get(j);
                ids.set(j, ids.get(j - 1));
                ids.set(j - 1, ii);
            }
        }
        return ids;
    }

    /** Closest established constrained edge properly crossed by the open segment {@code a-b}. */
    private static long nearestCrossingConstraint(final Mesh mesh, final int a, final int b) {
        final Point2 pa = mesh.pts.get(a);
        final Point2 pb = mesh.pts.get(b);
        long best = -1;
        double bestT = Double.MAX_VALUE;
        for (final long key : mesh.constrained) {
            final int c = (int) (key >>> 32);
            final int d = (int) (key & 0xFFFF_FFFFL);
            if (c == a || c == b || d == a || d == b || !edgeExists(mesh.tris, c, d)) {
                continue;
            }
            final Point2 pc = mesh.pts.get(c);
            final Point2 pd = mesh.pts.get(d);
            if (!segmentsCross(pa, pb, pc, pd)) {
                continue;
            }
            final double t = crossParam(pa, pb, pc, pd);
            if (t < bestT) {
                bestT = t;
                best = key;
            }
        }
        return best;
    }

    /**
     * Inserts the intersection point of segments {@code (a,b)} and {@code (c,d)} on the
     * constrained edge {@code (c,d)}: the adjacent triangles are split around the new vertex
     * and the ledger trades {@code (c,d)} for {@code (c,w)} plus {@code (w,d)}. Returns the
     * new or reused vertex index, or {@code -1} when the edge is missing.
     */
    private static int splitEdgeAtCrossing(final Mesh mesh, final int c, final int d, final int a, final int b) {
        final Point2 pa = mesh.pts.get(a);
        final Point2 pb = mesh.pts.get(b);
        final Point2 pc = mesh.pts.get(c);
        final Point2 pd = mesh.pts.get(d);
        final double t = crossParam(pa, pb, pc, pd);
        final double s = crossParam(pc, pd, pa, pb);
        if (!(t > 0.0 && t < 1.0) || !(s > 0.0 && s < 1.0)) {
            return -1;
        }
        final double wu = pa.x() + t * (pb.x() - pa.x());
        final double wv = pa.y() + t * (pb.y() - pa.y());
        final int near = nearestWithin(mesh, wu, wv, ON_EPS);
        if (near >= 0) {
            return near;
        }
        return insertOnEdge(mesh, c, d, wu, wv);
    }

    /**
     * Splits the 1–2 triangles adjacent to edge {@code (c,d)} around a new point placed at
     * {@code (u,v)} (expected on or very near the edge). A constrained {@code (c,d)} is
     * replaced by its two constrained halves — established constraints are never dropped.
     */
    private static int insertOnEdge(final Mesh mesh, final int c, final int d, final double u, final double v) {
        final List<Integer> adjacent = new ArrayList<>(2);
        final List<Integer> apexes = new ArrayList<>(2);
        final long key = undirected(c, d);
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            for (int e = 0; e < 3; e++) {
                if (undirected(t[e], t[(e + 1) % 3]) == key) {
                    adjacent.add(i);
                    apexes.add(t[(e + 2) % 3]);
                    break;
                }
            }
        }
        if (adjacent.isEmpty()) {
            return -1;
        }
        final int w = mesh.pts.size();
        mesh.pts.add(new Point2((float) u, (float) v));
        for (int k = adjacent.size() - 1; k >= 0; k--) {
            mesh.tris.remove((int) adjacent.get(k));
        }
        for (final int apex : apexes) {
            mesh.tris.add(oriented(mesh.pts, c, w, apex));
            mesh.tris.add(oriented(mesh.pts, w, d, apex));
        }
        if (mesh.constrained.remove(key)) {
            mesh.constrained.add(undirected(c, w));
            mesh.constrained.add(undirected(w, d));
        }
        return w;
    }

    /**
     * Cavity insertion of the constraint {@code a-b}: removes the strip of triangles crossed
     * by the segment and re-triangulates each of the two resulting pseudo-polygons so the
     * segment becomes the shared boundary edge. Returns {@code false} — without touching the
     * mesh — when the strip is degenerate or its cavity is not a simple polygon.
     */
    private static boolean insertConstraint(final Mesh mesh, final int a, final int b) {
        final Point2 pa = mesh.pts.get(a);
        final Point2 pb = mesh.pts.get(b);
        // Strip = every triangle whose interior the open segment passes through. A triangle
        // is crossed iff one of its edges is properly crossed by the segment: vertices lying
        // on the segment were split out beforehand, so a strip entry never hinges on a
        // grazing vertex touch. Endpoint stars must be represented or the segment left the
        // star along an edge — a degenerate layout the caller retries with a Steiner split.
        final List<Integer> strip = new ArrayList<>();
        boolean hasA = false;
        boolean hasB = false;
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            for (int e = 0; e < 3; e++) {
                final int x = t[e];
                final int y = t[(e + 1) % 3];
                if (x != a && x != b && y != a && y != b && segmentsCross(pa, pb, mesh.pts.get(x), mesh.pts.get(y))) {
                    strip.add(i);
                    hasA |= t[0] == a || t[1] == a || t[2] == a;
                    hasB |= t[0] == b || t[1] == b || t[2] == b;
                    break;
                }
            }
        }
        if (strip.isEmpty()) {
            return true; // the segment travels along existing edges; nothing to carve
        }
        if (!hasA || !hasB) {
            return false;
        }
        final Map<Long, int[]> edgeToTris = buildEdgeAdjacency(mesh.tris);

        // An established constraint sitting strictly inside the cavity (both adjacent
        // triangles in the strip) would be silently deleted by the carve. Refuse instead:
        // the caller falls back to flips/Steiner, which can never break a constraint.
        final Set<Integer> stripSet = new HashSet<>(strip);
        for (final int ti : strip) {
            final int[] t = mesh.tris.get(ti);
            for (int e = 0; e < 3; e++) {
                final long key = undirected(t[e], t[(e + 1) % 3]);
                if (!mesh.constrained.contains(key)) {
                    continue;
                }
                final int[] owners = edgeToTris.get(key);
                if (owners != null && owners[1] >= 0 && stripSet.contains(owners[0]) && stripSet.contains(owners[1])) {
                    return false;
                }
            }
        }
        // Cavity boundary = edges used exactly once inside the strip; it must form a single
        // cycle through a and b (degree 2 everywhere) or the cavity is pinched.
        final Map<Long, int[]> boundary = new HashMap<>();
        final Map<Long, Integer> useCount = new HashMap<>();
        for (final int ti : strip) {
            final int[] t = mesh.tris.get(ti);
            for (int e = 0; e < 3; e++) {
                final int x = t[e];
                final int y = t[(e + 1) % 3];
                final long key = undirected(x, y);
                boundary.putIfAbsent(key, new int[] {x, y});
                useCount.merge(key, 1, Integer::sum);
            }
        }
        final Map<Integer, List<Integer>> adj = new HashMap<>();
        for (final Map.Entry<Long, int[]> entry : boundary.entrySet()) {
            if (useCount.get(entry.getKey()) != 1) {
                continue;
            }
            final int[] e = entry.getValue();
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            adj.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
        }
        final List<Integer> chain1 = walkChain(adj, a, b);
        if (chain1 == null) {
            return false;
        }
        // The second chain is whatever route from a to b avoids chain1's interior vertices.
        final Set<Integer> blocked = new HashSet<>(chain1);
        blocked.add(a);
        blocked.add(b);
        final List<Integer> chain2 = walkChainExcept(adj, a, b, blocked);
        if (chain2 == null) {
            return false;
        }
        // The two chains must partition the cavity boundary — a leftover vertex means the
        // strip pinched somewhere and re-triangulating it would not fill the cavity.
        final Set<Integer> covered = new HashSet<>(blocked);
        covered.addAll(chain2);
        for (final Map.Entry<Integer, List<Integer>> entry : adj.entrySet()) {
            if (!covered.contains(entry.getKey())) {
                return false;
            }
        }

        final Set<Integer> dead = new HashSet<>(strip);
        final int[] cursor = {0};
        mesh.tris.removeIf(t -> dead.contains(cursor[0]++));
        triangulatePolygon(mesh, a, b, chain1);
        triangulatePolygon(mesh, a, b, chain2);
        return true;
    }

    /**
     * Promotes every existing triangle edge that lies on a constraint's open segment to
     * constrained. A segment split at Steiner points is satisfied by a chain whose links are
     * real mesh edges; marking them keeps the whole chain load-bearing for domain and mask
     * filtering.
     */
    private static void markCollinearChains(final Mesh mesh, final List<int[]> constraints) {
        for (final int[] c : constraints) {
            final int a = c[0];
            final int b = c[1];
            if (a == b || a >= mesh.pts.size() || b >= mesh.pts.size() || mesh.isSuper(a) || mesh.isSuper(b)) {
                continue;
            }
            final Point2 pa = mesh.pts.get(a);
            final Point2 pb = mesh.pts.get(b);
            final double abx = pb.x() - pa.x();
            final double aby = pb.y() - pa.y();
            final double len = Math.hypot(abx, aby);
            if (len < 1.0e-12) {
                continue;
            }
            // Same tolerances as the caller's subdivided-chain acceptance test, or chain edges
            // it would accept could still be dropped by domain/mask filtering.
            final double tol = Math.max(ON_EPS, len * 1.0e-4);
            for (final int[] t : mesh.tris) {
                for (int e = 0; e < 3; e++) {
                    final int x = t[e];
                    final int y = t[(e + 1) % 3];
                    if (mesh.isSuper(x) || mesh.isSuper(y)) {
                        continue;
                    }
                    if (onSegment(pa, pb, mesh.pts.get(x), len, abx, aby, tol)
                            && onSegment(pa, pb, mesh.pts.get(y), len, abx, aby, tol)) {
                        mesh.constrained.add(undirected(x, y));
                    }
                }
            }
        }
    }

    /** Whether {@code p} lies on the closed segment {@code pa-pb} within {@code tol}. */
    private static boolean onSegment(
            final Point2 pa,
            final Point2 pb,
            final Point2 p,
            final double len,
            final double abx,
            final double aby,
            final double tol) {
        final double t = ((p.x() - pa.x()) * abx + (p.y() - pa.y()) * aby) / (len * len);
        if (t < -1.0e-4 || t > 1.0001) {
            return false;
        }
        final double perp = Math.abs((p.x() - pa.x()) * aby - (p.y() - pa.y()) * abx) / len;
        return perp < tol;
    }

    /** Undirected edge → up to two owning triangle indices. */
    private static Map<Long, int[]> buildEdgeAdjacency(final List<int[]> tris) {
        final Map<Long, int[]> map = new HashMap<>();
        for (int i = 0; i < tris.size(); i++) {
            final int[] t = tris.get(i);
            for (int e = 0; e < 3; e++) {
                final long key = undirected(t[e], t[(e + 1) % 3]);
                final int[] owners = map.get(key);
                if (owners == null) {
                    map.put(key, new int[] {i, -1});
                } else if (owners[1] < 0 && owners[0] != i) {
                    owners[1] = i;
                }
            }
        }
        return map;
    }

    /**
     * Boundary path from {@code a} to {@code b} in the degree-2 adjacency, or {@code null}
     * when the walk dies or loops before reaching {@code b}.
     */
    private static List<Integer> walkChain(final Map<Integer, List<Integer>> adj, final int a, final int b) {
        final List<Integer> nbs = adj.get(a);
        if (nbs == null || nbs.size() != 2) {
            return null;
        }
        final List<Integer> chain = new ArrayList<>();
        int prev = a;
        int cur = nbs.get(0);
        final Set<Integer> visited = new HashSet<>();
        visited.add(a);
        while (cur != b) {
            if (!visited.add(cur)) {
                return null;
            }
            chain.add(cur);
            final List<Integer> nexts = adj.get(cur);
            if (nexts == null || nexts.size() != 2) {
                return null;
            }
            final int nxt = nexts.get(0) == prev ? nexts.get(1) : nexts.get(0);
            if (nxt == prev) {
                return null;
            }
            prev = cur;
            cur = nxt;
        }
        return chain;
    }

    /** Same boundary walk but forbidden from stepping into vertices of {@code blocked}. */
    private static List<Integer> walkChainExcept(
            final Map<Integer, List<Integer>> adj, final int a, final int b, final Set<Integer> blocked) {
        final List<Integer> nbs = adj.get(a);
        if (nbs == null || nbs.size() != 2) {
            return null;
        }
        int start = -1;
        for (final int n : nbs) {
            if (!blocked.contains(n)) {
                start = n;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        final List<Integer> chain = new ArrayList<>();
        int prev = a;
        int cur = start;
        final Set<Integer> visited = new HashSet<>(blocked);
        visited.remove(b); // b is the destination, not a blocked vertex
        visited.add(a);
        while (cur != b) {
            if (!visited.add(cur)) {
                return null;
            }
            chain.add(cur);
            final List<Integer> nexts = adj.get(cur);
            if (nexts == null) {
                return null;
            }
            int nxt = -1;
            for (final int n : nexts) {
                if (n != prev && !visited.contains(n)) {
                    nxt = n;
                    break;
                }
            }
            if (nxt < 0) {
                return null;
            }
            prev = cur;
            cur = nxt;
        }
        return chain;
    }

    /**
     * Ear-clips the pseudo-polygon {@code [a, chain..., b]} into triangles. The chain is one
     * side of a carved strip, so the polygon is simple; when numerics hide every ear the most
     * convex vertex is clipped anyway to guarantee forward progress.
     */
    private static void triangulatePolygon(final Mesh mesh, final int a, final int b, final List<Integer> chain) {
        final List<Integer> poly = new ArrayList<>(chain.size() + 2);
        poly.add(a);
        poly.addAll(chain);
        poly.add(b);
        if (poly.size() < 3) {
            return;
        }
        if (poly.size() == 3) {
            mesh.tris.add(oriented(mesh.pts, poly.get(0), poly.get(1), poly.get(2)));
            return;
        }
        double signed = 0.0;
        for (int i = 0; i < poly.size(); i++) {
            final Point2 p = mesh.pts.get(poly.get(i));
            final Point2 q = mesh.pts.get(poly.get((i + 1) % poly.size()));
            signed += p.x() * q.y() - q.x() * p.y();
        }
        if (signed == 0.0) {
            return;
        }
        final double s = Math.signum(signed);
        int guard = poly.size() * poly.size() + 16;
        while (poly.size() > 3 && guard-- > 0) {
            final int n = poly.size();
            int ear = -1;
            for (int i = 0; i < n; i++) {
                final int c = poly.get(i);
                if (c == a || c == b) {
                    continue; // the closing edge a-b must survive: endpoints are never ears
                }
                final int p = poly.get((i + n - 1) % n);
                final int nx = poly.get((i + 1) % n);
                final double o = orient(mesh.pts.get(p), mesh.pts.get(c), mesh.pts.get(nx)) * s;
                if (o <= 1.0e-18) {
                    continue;
                }
                boolean blocked = false;
                for (int j = 0; j < n; j++) {
                    if (j == i || j == (i + n - 1) % n || j == (i + 1) % n) {
                        continue;
                    }
                    if (insideTri(
                            mesh.pts,
                            mesh.pts.get(p),
                            mesh.pts.get(c),
                            mesh.pts.get(nx),
                            mesh.pts.get(poly.get(j)),
                            s)) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    ear = i;
                    break;
                }
            }
            if (ear < 0) {
                // No clean ear (float degeneracy): clip the most convex vertex regardless,
                // endpoints only when nothing else can move — coverage beats the constraint.
                double best = -Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    final int c = poly.get(i);
                    final int p = poly.get((i + n - 1) % n);
                    final int nx = poly.get((i + 1) % n);
                    double o = orient(mesh.pts.get(p), mesh.pts.get(c), mesh.pts.get(nx)) * s;
                    if (c == a || c == b) {
                        o -= 1.0e30;
                    }
                    if (o > best) {
                        best = o;
                        ear = i;
                    }
                }
                if (ear < 0) {
                    return;
                }
            }
            final int p = poly.get((ear + n - 1) % n);
            final int c = poly.get(ear);
            final int nx = poly.get((ear + 1) % n);
            mesh.tris.add(oriented(mesh.pts, p, c, nx));
            poly.remove(ear);
        }
        if (poly.size() == 3) {
            mesh.tris.add(oriented(mesh.pts, poly.get(0), poly.get(1), poly.get(2)));
        }
    }

    /** Strict containment of {@code r} in triangle {@code (p,c,n)} wound with sign {@code s}. */
    private static boolean insideTri(
            final List<Point2> pts, final Point2 p, final Point2 c, final Point2 n, final Point2 r, final double s) {
        return orient(p, c, r) * s > 0.0 && orient(c, n, r) * s > 0.0 && orient(n, p, r) * s > 0.0;
    }

    /**
     * Midpoint Steiner fallback: returns the existing vertex at the midpoint when present,
     * else inserts it — on-edge split when it lands on an edge (constrained or not), plain
     * triangle split otherwise. Never removes a constrained edge.
     */
    private static int insertSteiner(final Mesh mesh, final int a, final int b) {
        final Point2 pa = mesh.pts.get(a);
        final Point2 pb = mesh.pts.get(b);
        final double mu = (pa.x() + pb.x()) / 2;
        final double mv = (pa.y() + pb.y()) / 2;
        final int existing = nearestWithin(mesh, mu, mv, ON_EPS);
        if (existing >= 0) {
            return existing;
        }
        return insertPointConstrained(mesh, mu, mv);
    }

    /**
     * Inserts a vertex at {@code (u,v)} splitting the containing edge or triangle. The
     * containing edge keeps its constrained flag across the split (as two halves); no
     * triangle that carries a constrained edge is ever deleted outright.
     */
    private static int insertPointConstrained(final Mesh mesh, final double u, final double v) {
        for (int i = 0; i < mesh.tris.size(); i++) {
            final int[] t = mesh.tris.get(i);
            final Point2 p0 = mesh.pts.get(t[0]);
            final Point2 p1 = mesh.pts.get(t[1]);
            final Point2 p2 = mesh.pts.get(t[2]);
            final Point2 q = new Point2((float) u, (float) v);
            final double o0 = orient(p0, p1, q);
            final double o1 = orient(p1, p2, q);
            final double o2 = orient(p2, p0, q);
            final double area = orient(p0, p1, p2);
            final double tol = Math.abs(area) * 1.0e-6 + 1.0e-20;
            if (o0 < -tol || o1 < -tol || o2 < -tol) {
                continue;
            }
            // On an edge (within eps of the shared side)? Prefer an edge split so collinear
            // constraints stay exactly supported.
            for (int e = 0; e < 3; e++) {
                final int x = t[e];
                final int y = t[(e + 1) % 3];
                final Point2 px = mesh.pts.get(x);
                final Point2 py = mesh.pts.get(y);
                final double ex = py.x() - px.x();
                final double ey = py.y() - px.y();
                final double elen = Math.hypot(ex, ey);
                if (elen < 1.0e-12) {
                    continue;
                }
                final double perp = Math.abs((u - px.x()) * ey - (v - px.y()) * ex) / elen;
                final double tparam = ((u - px.x()) * ex + (v - px.y()) * ey) / (elen * elen);
                if (perp < ON_EPS && tparam > 0.0 && tparam < 1.0) {
                    return insertOnEdge(mesh, x, y, u, v);
                }
            }
            mesh.tris.remove(i);
            final int w = mesh.pts.size();
            mesh.pts.add(q);
            mesh.tris.add(oriented(mesh.pts, t[0], t[1], w));
            mesh.tris.add(oriented(mesh.pts, t[1], t[2], w));
            mesh.tris.add(oriented(mesh.pts, t[2], t[0], w));
            return w;
        }
        return -1;
    }

    private static int nearestWithin(final Mesh mesh, final double u, final double v, final double eps) {
        for (int i = 0; i < mesh.pts.size(); i++) {
            if (mesh.isSuper(i)) {
                continue;
            }
            final Point2 p = mesh.pts.get(i);
            if (Math.abs(p.x() - u) < eps && Math.abs(p.y() - v) < eps) {
                return i;
            }
        }
        return -1;
    }

    /** Bounded Lawson pass: restores Delaunay quality without ever touching a constraint. */
    private static void restoreDelaunay(final Mesh mesh) {
        for (int pass = 0; pass < 64; pass++) {
            int flips = 0;
            for (int i = 0; i < mesh.tris.size(); i++) {
                for (int e = 0; e < 3; e++) {
                    if (tryFlip(mesh, i, e)) {
                        flips++;
                    }
                }
            }
            if (flips == 0) {
                return;
            }
        }
    }

    /**
     * Flips edge {@code e} of triangle {@code ti} when it is interior, unconstrained, its quad
     * is strictly convex, and the opposite vertex lies inside this triangle's circumcircle.
     */
    private static boolean tryFlip(final Mesh mesh, final int ti, final int e) {
        final int[] t1 = mesh.tris.get(ti);
        final int x = t1[e];
        final int y = t1[(e + 1) % 3];
        final int p = t1[(e + 2) % 3];
        final long key = undirected(x, y);
        if (mesh.constrained.contains(key)) {
            return false;
        }
        int tj = -1;
        int q = -1;
        for (int i = 0; i < mesh.tris.size(); i++) {
            if (i == ti) {
                continue;
            }
            final int[] t = mesh.tris.get(i);
            for (int f = 0; f < 3; f++) {
                if (undirected(t[f], t[(f + 1) % 3]) == key) {
                    tj = i;
                    q = t[(f + 2) % 3];
                    break;
                }
            }
            if (tj >= 0) {
                break;
            }
        }
        if (tj < 0 || q == p) {
            return false;
        }
        if (!convexQuad(mesh.pts, p, x, q, y)) {
            return false;
        }
        if (!circumcircleContains(mesh.pts, t1, mesh.pts.get(q))) {
            return false;
        }
        final int[] n1 = oriented(mesh.pts, p, q, x);
        final int[] n2 = oriented(mesh.pts, p, y, q);
        if (Math.abs(orient(mesh.pts.get(n1[0]), mesh.pts.get(n1[1]), mesh.pts.get(n1[2]))) < 1.0e-18
                || Math.abs(orient(mesh.pts.get(n2[0]), mesh.pts.get(n2[1]), mesh.pts.get(n2[2]))) < 1.0e-18) {
            return false;
        }
        mesh.tris.set(ti, n1);
        mesh.tris.set(tj, n2);
        return true;
    }

    private static boolean edgeExists(final List<int[]> tris, final int a, final int b) {
        final long key = undirected(a, b);
        for (final int[] t : tris) {
            for (int e = 0; e < 3; e++) {
                if (undirected(t[e], t[(e + 1) % 3]) == key) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Strict interior crossing: both endpoints of each segment on opposite sides of the other. */
    private static boolean segmentsCross(final Point2 a, final Point2 b, final Point2 x, final Point2 y) {
        final double o1 = orient(a, b, x);
        final double o2 = orient(a, b, y);
        final double o3 = orient(x, y, a);
        final double o4 = orient(x, y, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    /**
     * Parameter along segment {@code a-b} of its intersection with the line through
     * {@code x-y}; {@code NaN} when the lines are parallel.
     */
    private static double crossParam(final Point2 a, final Point2 b, final Point2 x, final Point2 y) {
        final double abx = b.x() - a.x();
        final double aby = b.y() - a.y();
        final double ex = y.x() - x.x();
        final double ey = y.y() - x.y();
        final double denom = abx * ey - aby * ex;
        if (Math.abs(denom) < 1.0e-30) {
            return Double.NaN;
        }
        return ((x.x() - a.x()) * ey - (x.y() - a.y()) * ex) / denom;
    }

    /** Quad {@code p-x-q-y} strictly convex: all four turn cross products share a sign. */
    private static boolean convexQuad(final List<Point2> pts, final int p, final int x, final int q, final int y) {
        final double o1 = orient(pts.get(p), pts.get(x), pts.get(q));
        final double o2 = orient(pts.get(x), pts.get(q), pts.get(y));
        final double o3 = orient(pts.get(q), pts.get(y), pts.get(p));
        final double o4 = orient(pts.get(y), pts.get(p), pts.get(x));
        return (o1 > 0 && o2 > 0 && o3 > 0 && o4 > 0) || (o1 < 0 && o2 < 0 && o3 < 0 && o4 < 0);
    }

    private static double orient(final Point2 a, final Point2 b, final Point2 c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static long undirected(final int a, final int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
    }
}
