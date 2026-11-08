package dev.turboism.plugin.turbomesh.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic planner fuzz: irregular star-ish source silhouettes plus random alpha blobs,
 * the same generator the CDT rewrite was shaken out with. Every seed must plan successfully
 * AND satisfy the output invariants — closed silhouette, CCW non-overlapping triangles whose
 * area adds up to the domain, nothing outside the source outline, no duplicate vertices.
 */
class TurboMeshFuzzTest {

    private static final int SEEDS = 500;

    // ---------- generators (mirrors the /tmp/fz harness) ----------

    /** Fan-triangulated irregular concave polygon around the centre. */
    private static ArtMeshGeometry sourceGeometry(final Random r) {
        final int n = 8 + r.nextInt(10);
        final List<Point2> uvs = new ArrayList<>();
        final List<Point2> pos = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        uvs.add(new Point2(0.5f, 0.5f));
        for (int i = 0; i < n; i++) {
            final double ang = 2 * Math.PI * i / n;
            final double rad = 0.25 + 0.2 * r.nextDouble();
            uvs.add(new Point2((float) (0.5 + rad * Math.cos(ang)), (float) (0.5 + rad * Math.sin(ang))));
        }
        for (final Point2 p : uvs) {
            pos.add(new Point2(p.x() * 100, p.y() * 100));
        }
        for (int i = 0; i < n; i++) {
            idx.add(0);
            idx.add(1 + i);
            idx.add(1 + (i + 1) % n);
        }
        return new ArtMeshGeometry(pos, uvs, idx);
    }

    /** Random union of ellipse blobs as the texture-alpha mask, with line-art stripes. */
    private static DomainMask maskFor(final ArtMeshGeometry src, final Random r, final PlacementSettings s) {
        final int w = 128;
        final int h = 128;
        final int[] argb = new int[w * h];
        final int blobs = 1 + r.nextInt(4);
        final double[][] b = new double[blobs][4];
        for (int k = 0; k < blobs; k++) {
            b[k][0] = 0.2 + 0.6 * r.nextDouble();
            b[k][1] = 0.2 + 0.6 * r.nextDouble();
            b[k][2] = 0.1 + 0.3 * r.nextDouble();
            b[k][3] = 0.1 + 0.3 * r.nextDouble();
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final double u = (x + 0.5) / w;
                final double v = (y + 0.5) / h;
                boolean in = false;
                for (final double[] e : b) {
                    final double dx = (u - e[0]) / e[2];
                    final double dy = (v - e[1]) / e[3];
                    if (dx * dx + dy * dy < 1) {
                        in = true;
                    }
                }
                final int lum = Math.abs(((int) (u * 20)) % 5) == 0 ? 20 : 220;
                argb[y * w + x] = in ? (0xFF << 24) | (lum << 16) | (lum << 8) | lum : 0;
            }
        }
        double u0 = 1, v0 = 1, u1 = 0, v1 = 0;
        for (int i = 1; i < src.uvs().size(); i++) {
            final Point2 p = src.uvs().get(i);
            u0 = Math.min(u0, p.x());
            v0 = Math.min(v0, p.y());
            u1 = Math.max(u1, p.x());
            v1 = Math.max(v1, p.y());
        }
        return DomainMask.fromPixels(
                argb,
                w,
                h,
                u0 - DomainMask.PAD_UV,
                v0 - DomainMask.PAD_UV,
                u1 + DomainMask.PAD_UV,
                v1 + DomainMask.PAD_UV,
                s.rasterMaxDim(),
                8);
    }

    // ---------- invariants ----------

    private static double orient(final Point2 a, final Point2 b, final Point2 c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static boolean pointInPolygon(final List<Point2> ring, final double u, final double v) {
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

    /** Signed shoelace area of a polygon. */
    private static double ringArea(final List<Point2> ring) {
        double area = 0;
        for (int i = 0; i < ring.size(); i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get((i + 1) % ring.size());
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area / 2.0;
    }

    /** Source silhouette loops as UV-coordinate polygons. */
    private static List<List<Point2>> sourceLoops(final ArtMeshGeometry source) {
        final List<List<Point2>> out = new ArrayList<>();
        for (final int[] loop : MeshTopology.boundaryLoops(source.triangleIndices())) {
            final List<Point2> ring = new ArrayList<>(loop.length);
            for (final int i : loop) {
                ring.add(source.uvs().get(i));
            }
            out.add(ring);
        }
        return out;
    }

    /**
     * Depth of {@code ring} under other rings — even nesting means silhouette, odd means hole.
     */
    private static int nestingDepth(final List<List<Point2>> rings, final int self) {
        final List<Point2> ring = rings.get(self);
        final Point2 a = ring.get(0);
        final Point2 b = ring.get(1 % ring.size());
        final double u = (a.x() + b.x()) / 2.0;
        final double v = (a.y() + b.y()) / 2.0;
        int depth = 0;
        for (int j = 0; j < rings.size(); j++) {
            if (j != self && pointInPolygon(rings.get(j), u, v)) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Asserts the output plan honours every invariant of the placement contract.
     *
     * @param slackUv how far past the source outline a centroid may sit — boundary resampling
     *     is allowed to deviate by the sagitta bound plus rasterisation slack
     */
    private static void assertPlanInvariants(
            final TurboMeshPlanner.PlacementPlan plan, final ArtMeshGeometry source, final double slackUv) {
        assertPlanInvariants(plan, source, slackUv, true);
    }

    private static void assertPlanInvariants(
            final TurboMeshPlanner.PlacementPlan plan,
            final ArtMeshGeometry source,
            final double slackUv,
            final boolean areaFillsDomain) {
        final ArtMeshGeometry g = plan.geometry();
        final List<Integer> idx = g.triangleIndices();
        final List<Point2> pts = g.uvs();
        assertEquals(0, idx.size() % 3);
        assertTrue(!idx.isEmpty(), "plan must emit triangles");

        // 1. All indices in range, every triangle CCW and non-degenerate.
        double area = 0;
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            final int ia = idx.get(t);
            final int ib = idx.get(t + 1);
            final int ic = idx.get(t + 2);
            assertTrue(ia >= 0 && ia < pts.size());
            assertTrue(ib >= 0 && ib < pts.size());
            assertTrue(ic >= 0 && ic < pts.size());
            final Point2 a = pts.get(ia);
            final Point2 b = pts.get(ib);
            final Point2 c = pts.get(ic);
            assertTrue(orient(a, b, c) > 0, "triangle must be CCW: " + t);
            area += orient(a, b, c) / 2.0;
        }

        // 2. No duplicated output vertices.
        final Set<Long> seen = new HashSet<>();
        for (final Point2 p : pts) {
            final long key = (Float.floatToRawIntBits(p.x()) & 0xFFFF_FFFFL) << 32
                    | (Float.floatToRawIntBits(p.y()) & 0xFFFF_FFFFL);
            assertTrue(seen.add(key), "duplicate output point " + p);
        }

        // 3. Manifold boundary: no edge shared by more than two triangles, and the boundary
        //    decomposes into closed loops (pinch vertices with even degree are legitimate —
        //    a thin corridor cut by the mask can leave the silhouette touching itself).
        final java.util.Map<Long, Integer> edgeUse = new java.util.HashMap<>();
        final java.util.Map<Integer, java.util.Set<Integer>> bndAdj = new java.util.HashMap<>();
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            for (int e = 0; e < 3; e++) {
                final int x = idx.get(t + e);
                final int y = idx.get(t + (e + 1) % 3);
                final long k = ((long) Math.min(x, y) << 32) | Math.max(x, y);
                edgeUse.merge(k, 1, Integer::sum);
            }
        }
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            for (int e = 0; e < 3; e++) {
                final int x = idx.get(t + e);
                final int y = idx.get(t + (e + 1) % 3);
                final long k = ((long) Math.min(x, y) << 32) | Math.max(x, y);
                assertTrue(edgeUse.get(k) <= 2, "edge used more than twice: " + k);
                if (edgeUse.get(k) == 1) {
                    bndAdj.computeIfAbsent(x, kk -> new HashSet<>()).add(y);
                    bndAdj.computeIfAbsent(y, kk -> new HashSet<>()).add(x);
                }
            }
        }
        for (final var e : bndAdj.entrySet()) {
            assertTrue(
                    e.getValue().size() % 2 == 0,
                    "boundary vertex must have even degree (closed loops): " + e.getKey());
        }
        assertTrue(!MeshTopology.boundaryLoops(idx).isEmpty(), "output must have a closed boundary");

        // 4. Coverage ≈ domain area: sum of triangle areas equals the silhouette outline area
        //    minus holes. The source loops are the ground truth; resampling slack goes into
        //    the tolerance.
        final List<List<Point2>> loops = sourceLoops(source);
        double domain = 0;
        for (int i = 0; i < loops.size(); i++) {
            domain += (nestingDepth(loops, i) % 2 == 0 ? 1 : -1) * Math.abs(ringArea(loops.get(i)));
        }
        if (areaFillsDomain) {
            // Geometry mode: the domain IS the silhouette — coverage must match it closely.
            final double relErr = Math.abs(area - domain) / Math.max(domain, 1.0e-9);
            assertTrue(
                    relErr < 0.15,
                    "triangle area " + area + " must roughly equal domain area " + domain + " (relErr=" + relErr + ")");
        } else {
            // Texture mode: domain is silhouette ∩ alpha mask — coverage must not spill past
            // the outline (overlap would push the sum over), but may legitimately cover less.
            assertTrue(
                    area <= domain * 1.15 + 1.0e-4,
                    "triangle area " + area + " exceeds domain " + domain + " — triangles overlap or spill");
        }

        // 5. No triangle far outside the source silhouette: centroids must sit inside the
        //    union of outer loops minus holes, allowing resampling slack.
        final List<List<Point2>> outer = new ArrayList<>();
        final List<List<Point2>> holes = new ArrayList<>();
        for (int i = 0; i < loops.size(); i++) {
            (nestingDepth(loops, i) % 2 == 0 ? outer : holes).add(loops.get(i));
        }
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            final Point2 a = pts.get(idx.get(t));
            final Point2 b = pts.get(idx.get(t + 1));
            final Point2 c = pts.get(idx.get(t + 2));
            final double cu = (a.x() + b.x() + c.x()) / 3.0;
            final double cv = (a.y() + b.y() + c.y()) / 3.0;
            boolean in = false;
            for (final List<Point2> ring : outer) {
                if (pointInPolygon(ring, cu, cv) || distToRing(ring, cu, cv) <= slackUv) {
                    in = true;
                    break;
                }
            }
            assertTrue(in, "triangle centroid outside source silhouette: (" + cu + "," + cv + ")");
            for (final List<Point2> hole : holes) {
                final boolean insideHole = pointInPolygon(hole, cu, cv) && distToRing(hole, cu, cv) > slackUv;
                assertTrue(!insideHole, "triangle centroid inside hole: (" + cu + "," + cv + ")");
            }
        }
    }

    // ---------- fuzz ----------

    @Test
    void geometryModeFuzz500Seeds() {
        final PlacementSettings s = PlacementSettings.defaults();
        int fails = 0;
        final StringBuilder report = new StringBuilder();
        for (int seed = 0; seed < SEEDS; seed++) {
            final Random r = new Random(seed);
            final ArtMeshGeometry src = sourceGeometry(r);
            try {
                final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(src, s);
                assertPlanInvariants(plan, src, 0.02);
            } catch (Throwable e) {
                fails++;
                report.append("seed=").append(seed).append(": ").append(e).append('\n');
                if (fails > 10) {
                    break;
                }
            }
        }
        assertEquals(0, fails, "geometry fuzz failures:\n" + report);
    }

    @Test
    void textureMaskModeFuzz500Seeds() {
        final PlacementSettings s = PlacementSettings.defaults();
        int fails = 0;
        final StringBuilder report = new StringBuilder();
        for (int seed = 0; seed < SEEDS; seed++) {
            final Random r = new Random(seed);
            final ArtMeshGeometry src = sourceGeometry(r);
            final DomainMask mask = maskFor(src, r, s);
            try {
                final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(src, mask, s);
                assertPlanInvariants(plan, src, 0.02, false);
            } catch (Throwable e) {
                fails++;
                report.append("seed=").append(seed).append(": ").append(e).append('\n');
                if (fails > 10) {
                    break;
                }
            }
        }
        assertEquals(0, fails, "texture fuzz failures:\n" + report);
    }

    @Test
    void plannerDeterministicAcrossFuzzSubset() throws TurboMeshPlanner.PlacementException {
        final PlacementSettings s = PlacementSettings.defaults();
        for (int seed = 0; seed < 40; seed++) {
            final Random r = new Random(seed);
            final ArtMeshGeometry src = sourceGeometry(r);
            final TurboMeshPlanner.PlacementPlan a = TurboMeshPlanner.plan(src, s);
            final TurboMeshPlanner.PlacementPlan b = TurboMeshPlanner.plan(src, s);
            assertEquals(a.geometry().uvs(), b.geometry().uvs(), "nondeterministic uvs at seed " + seed);
            assertEquals(
                    a.geometry().triangleIndices(),
                    b.geometry().triangleIndices(),
                    "nondeterministic tris at seed " + seed);
        }
    }
}
