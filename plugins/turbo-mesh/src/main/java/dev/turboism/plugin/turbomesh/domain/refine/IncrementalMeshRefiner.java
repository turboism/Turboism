package dev.turboism.plugin.turbomesh.domain.refine;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spine-style incremental mesh refinement (Generate): subdivides existing triangles to
 * progressively increase mesh density while strictly preserving all existing vertices,
 * boundary edges, and manifold connectivity.
 *
 * <p>Never tears or fragments geometry: operations are strictly 1-to-3 interior splits and
 * constrained Delaunay edge flips on existing triangles, ensuring zero boundary drift and
 * zero hole creation. Every new vertex records its barycentric coordinates within its original
 * triangle for lossless interpolation across all parameter keyforms.</p>
 */
public final class IncrementalMeshRefiner {

    private static final double EPS = 1.0e-9;
    private static final int MAX_FLIP_DEPTH = 32;

    private IncrementalMeshRefiner() {}

    /** Tunable parameters for one incremental refinement step. */
    public record RefinementOptions(
            double areaFraction, double maxAspectRatio, double minSpacingUv, int maxAddedPoints) {

        public RefinementOptions {
            if (!(areaFraction > 0.0) || !(maxAspectRatio > 1.0)) {
                throw new IllegalArgumentException("areaFraction and maxAspectRatio must be positive and > 1");
            }
            if (!(minSpacingUv > 0.0) || maxAddedPoints < 1) {
                throw new IllegalArgumentException("minSpacingUv must be positive and maxAddedPoints >= 1");
            }
        }

        /** Sensible defaults: refines triangles at or above average size or distorted, adding up to 80 points. */
        public static RefinementOptions defaults() {
            return new RefinementOptions(0.9, 1.8, 0.005, 80);
        }
    }

    /** Barycentric weights of a newly added vertex within its original triangle vertices. */
    public record BarycentricWeight(
            int vertexA, int vertexB, int vertexC, double weightA, double weightB, double weightC) {

        /**
         * Interpolates position for this new vertex across a list of vertex positions.
         *
         * @param positions list of positions indexed by vertex index
         * @return interpolated position
         */
        public Point2 interpolate(final List<Point2> positions) {
            final Point2 pa = positions.get(vertexA);
            final Point2 pb = positions.get(vertexB);
            final Point2 pc = positions.get(vertexC);
            final double x = weightA * pa.x() + weightB * pb.y() + weightC * pc.x();
            final double y = weightA * pa.y() + weightB * pb.y() + weightC * pc.y();
            return new Point2((float) (weightA * pa.x() + weightB * pb.x() + weightC * pc.x()), (float)
                    (weightA * pa.y() + weightB * pb.y() + weightC * pc.y()));
        }
    }

    /** Complete result of one refinement step. */
    public record RefinementResult(
            ArtMeshGeometry geometry,
            List<BarycentricWeight> newVertexWeights,
            int originalVertexCount,
            int addedPoints,
            int addedTriangles) {}

    /**
     * Incrementally refines {@code source} by subdividing triangles.
     *
     * @param source input geometry with consistent UVs, positions, and triangles
     * @param targetTriangles optional set of triangle indices (0..numTriangles-1) to restrict
     *     refinement to (e.g. from a user selection); {@code null} or empty refines the entire mesh
     * @param options refinement thresholds and point budget
     * @return refined geometry plus barycentric interpolation records for new vertices
     */
    public static RefinementResult refine(
            final ArtMeshGeometry source, final Set<Integer> targetTriangles, final RefinementOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");

        final List<Point2> uvs = new ArrayList<>(source.uvs());
        final List<Point2> positions = new ArrayList<>(source.positions());
        final List<Integer> srcIndices = source.triangleIndices();
        final int numSrcTriangles = srcIndices.size() / 3;
        final int origVertexCount = uvs.size();

        if (numSrcTriangles == 0 || origVertexCount < 3) {
            return new RefinementResult(source, List.of(), origVertexCount, 0, 0);
        }

        // 1. Build triangles list and edge adjacency.
        final List<Triangle> triangles = new ArrayList<>();
        final Map<Long, List<Integer>> edgeToTris = new HashMap<>();
        for (int t = 0; t < numSrcTriangles; t++) {
            final int a = srcIndices.get(t * 3);
            final int b = srcIndices.get(t * 3 + 1);
            final int c = srcIndices.get(t * 3 + 2);
            final Triangle tri = new Triangle(t, a, b, c, uvs);
            triangles.add(tri);
            linkEdge(edgeToTris, a, b, t);
            linkEdge(edgeToTris, b, c, t);
            linkEdge(edgeToTris, c, a, t);
        }

        // 2. Identify boundary edges (edges incident to exactly 1 triangle) as locked.
        final Set<Long> lockedEdges = new HashSet<>();
        for (final Map.Entry<Long, List<Integer>> entry : edgeToTris.entrySet()) {
            if (entry.getValue().size() == 1) {
                lockedEdges.add(entry.getKey());
            }
        }

        // 3. Score candidate triangles for subdivision.
        double totalArea = 0.0;
        int targetCount = 0;
        for (int t = 0; t < numSrcTriangles; t++) {
            if (targetTriangles == null || targetTriangles.isEmpty() || targetTriangles.contains(t)) {
                totalArea += triangles.get(t).area;
                targetCount++;
            }
        }
        final double avgArea = targetCount > 0 ? totalArea / targetCount : 0.0;
        final double areaThreshold = avgArea * options.areaFraction();

        final List<Candidate> candidates = new ArrayList<>();
        for (int t = 0; t < numSrcTriangles; t++) {
            if (targetTriangles != null && !targetTriangles.isEmpty() && !targetTriangles.contains(t)) {
                continue;
            }
            final Triangle tri = triangles.get(t);
            if (tri.area <= 1.0e-12) {
                continue;
            }
            final boolean large = tri.area >= areaThreshold;
            final boolean distorted = tri.aspectRatio >= options.maxAspectRatio();
            if (large || distorted) {
                candidates.add(new Candidate(t, tri.area, tri.aspectRatio));
            }
        }

        // Sort candidates by area descending (largest triangles refined first).
        candidates.sort((c1, c2) -> Double.compare(c2.area, c1.area));

        // 4. Subdivide triangles and perform Lawson flips.
        final List<BarycentricWeight> weights = new ArrayList<>();
        final double minSpacingSq = options.minSpacingUv() * options.minSpacingUv();
        int added = 0;

        for (final Candidate cand : candidates) {
            if (added >= options.maxAddedPoints()) {
                break;
            }
            final Triangle tri = triangles.get(cand.triangleIndex);
            if (!tri.active) {
                continue; // was already subdivided or flipped
            }

            // Find insertion point inside the triangle (circumcenter or centroid).
            final Point2 pa = uvs.get(tri.a);
            final Point2 pb = uvs.get(tri.b);
            final Point2 pc = uvs.get(tri.c);

            double[] uvP = circumcenter(pa, pb, pc);
            double[] bary = uvP != null ? barycentric(pa, pb, pc, uvP[0], uvP[1]) : null;

            // Use circumcenter if strictly inside the triangle; otherwise fall back to centroid.
            if (bary == null || bary[0] < 0.1 || bary[1] < 0.1 || bary[2] < 0.1) {
                uvP = new double[] {(pa.x() + pb.x() + pc.x()) / 3.0, (pa.y() + pb.y() + pc.y()) / 3.0};
                bary = new double[] {1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
            }

            final Point2 newUv = new Point2((float) uvP[0], (float) uvP[1]);

            // Check distance to all existing vertices to avoid clustering.
            boolean tooClose = false;
            for (final Point2 existing : uvs) {
                final double dx = existing.x() - newUv.x();
                final double dy = existing.y() - newUv.y();
                if (dx * dx + dy * dy < minSpacingSq) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }

            // Calculate interpolated position in document space.
            final Point2 posA = positions.get(tri.a);
            final Point2 posB = positions.get(tri.b);
            final Point2 posC = positions.get(tri.c);
            final Point2 newPos =
                    new Point2((float) (bary[0] * posA.x() + bary[1] * posB.x() + bary[2] * posC.x()), (float)
                            (bary[0] * posA.y() + bary[1] * posB.y() + bary[2] * posC.y()));

            final int newIdx = uvs.size();
            uvs.add(newUv);
            positions.add(newPos);
            weights.add(new BarycentricWeight(tri.a, tri.b, tri.c, bary[0], bary[1], bary[2]));
            added++;

            // Split triangle into 3: (a, b, newIdx), (b, c, newIdx), (c, a, newIdx).
            tri.active = false;
            unlinkTriangleEdges(edgeToTris, tri);

            final int t1 = triangles.size();
            final Triangle tri1 = new Triangle(t1, tri.a, tri.b, newIdx, uvs);
            triangles.add(tri1);
            linkTriangleEdges(edgeToTris, tri1);

            final int t2 = triangles.size();
            final Triangle tri2 = new Triangle(t2, tri.b, tri.c, newIdx, uvs);
            triangles.add(tri2);
            linkTriangleEdges(edgeToTris, tri2);

            final int t3 = triangles.size();
            final Triangle tri3 = new Triangle(t3, tri.c, tri.a, newIdx, uvs);
            triangles.add(tri3);
            linkTriangleEdges(edgeToTris, tri3);

            // Lawson flip along the outer edges of the 1-to-3 split.
            lawsonFlip(triangles, edgeToTris, lockedEdges, uvs, tri.a, tri.b, newIdx);
            lawsonFlip(triangles, edgeToTris, lockedEdges, uvs, tri.b, tri.c, newIdx);
            lawsonFlip(triangles, edgeToTris, lockedEdges, uvs, tri.c, tri.a, newIdx);
        }

        // 5. Gather all active triangles into the final geometry.
        final List<Integer> outIndices = new ArrayList<>();
        for (final Triangle tri : triangles) {
            if (tri.active) {
                outIndices.add(tri.a);
                outIndices.add(tri.b);
                outIndices.add(tri.c);
            }
        }

        final ArtMeshGeometry refined = new ArtMeshGeometry(positions, uvs, outIndices);
        final int addedTriangles = (outIndices.size() - srcIndices.size()) / 3;
        return new RefinementResult(refined, weights, origVertexCount, added, addedTriangles);
    }

    /**
     * Checks if edge {@code (a, b)} shared by triangle with vertex {@code p} should be flipped
     * with the adjacent triangle sharing {@code (a, b)} and vertex {@code d}.
     */
    private static void lawsonFlip(
            final List<Triangle> triangles,
            final Map<Long, List<Integer>> edgeToTris,
            final Set<Long> lockedEdges,
            final List<Point2> uvs,
            final int a,
            final int b,
            final int p) {
        final Deque<FlipEdge> stack = new ArrayDeque<>();
        stack.push(new FlipEdge(a, b, p));

        int depth = 0;
        while (!stack.isEmpty() && depth++ < MAX_FLIP_DEPTH) {
            final FlipEdge fe = stack.pop();
            final long key = edgeKey(fe.a, fe.b);
            if (lockedEdges.contains(key)) {
                continue; // locked boundary or constraint edge must never flip
            }

            final List<Integer> inc = edgeToTris.get(key);
            if (inc == null || inc.size() != 2) {
                continue;
            }

            final Triangle t1 = triangles.get(inc.get(0));
            final Triangle t2 = triangles.get(inc.get(1));
            if (!t1.active || !t2.active) {
                continue;
            }

            // Identify which triangle contains fe.p and find the opposite vertex d in the other.
            final int d = t1.oppositeVertex(fe.a, fe.b) == fe.p
                    ? t2.oppositeVertex(fe.a, fe.b)
                    : t1.oppositeVertex(fe.a, fe.b);
            if (d < 0 || d == fe.p) {
                continue;
            }

            final Point2 pa = uvs.get(fe.a);
            final Point2 pb = uvs.get(fe.b);
            final Point2 pp = uvs.get(fe.p);
            final Point2 pd = uvs.get(d);

            // Quadrilateral (a, p, b, d) must be strictly convex.
            if (!isStrictlyConvex(pa, pp, pb, pd)) {
                continue;
            }

            // In-circle test: does d lie inside the circumcircle of (a, b, p)?
            if (!inCircumcircle(pa, pb, pp, pd)) {
                continue;
            }

            // Flip edge (a, b) to (p, d).
            t1.active = false;
            t2.active = false;
            unlinkTriangleEdges(edgeToTris, t1);
            unlinkTriangleEdges(edgeToTris, t2);

            final int nt1 = triangles.size();
            final Triangle newT1 = new Triangle(nt1, fe.a, d, fe.p, uvs);
            triangles.add(newT1);
            linkTriangleEdges(edgeToTris, newT1);

            final int nt2 = triangles.size();
            final Triangle newT2 = new Triangle(nt2, fe.b, fe.p, d, uvs);
            triangles.add(newT2);
            linkTriangleEdges(edgeToTris, newT2);

            // Push the two new opposite edges to the stack.
            stack.push(new FlipEdge(fe.a, d, fe.p));
            stack.push(new FlipEdge(d, fe.b, fe.p));
        }
    }

    private record FlipEdge(int a, int b, int p) {}

    private static boolean isStrictlyConvex(final Point2 a, final Point2 b, final Point2 c, final Point2 d) {
        // Cross products must all have the same strict sign.
        final double cp1 = cross(a, b, c);
        final double cp2 = cross(b, c, d);
        final double cp3 = cross(c, d, a);
        final double cp4 = cross(d, a, b);
        return (cp1 > EPS && cp2 > EPS && cp3 > EPS && cp4 > EPS)
                || (cp1 < -EPS && cp2 < -EPS && cp3 < -EPS && cp4 < -EPS);
    }

    private static double cross(final Point2 o, final Point2 a, final Point2 b) {
        return (double) (a.x() - o.x()) * (b.y() - o.y()) - (double) (a.y() - o.y()) * (b.x() - o.x());
    }

    /** True when d lies strictly inside the circumcircle of counter-clockwise (a, b, c). */
    private static boolean inCircumcircle(final Point2 a, final Point2 b, final Point2 c, final Point2 d) {
        // Orient (a, b, c) CCW.
        Point2 v1 = a;
        Point2 v2 = b;
        Point2 v3 = c;
        if (cross(v1, v2, v3) < 0) {
            v2 = c;
            v3 = b;
        }
        final double adx = v1.x() - d.x();
        final double ady = v1.y() - d.y();
        final double bdx = v2.x() - d.x();
        final double bdy = v2.y() - d.y();
        final double cdx = v3.x() - d.x();
        final double cdy = v3.y() - d.y();

        final double det = (adx * adx + ady * ady) * (bdx * cdy - cdx * bdy)
                - (bdx * bdx + bdy * bdy) * (adx * cdy - cdx * ady)
                + (cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady);
        return det > 1.0e-9;
    }

    private static double[] circumcenter(final Point2 a, final Point2 b, final Point2 c) {
        final double d = 2.0 * (a.x() * (b.y() - c.y()) + b.x() * (c.y() - a.y()) + c.x() * (a.y() - b.y()));
        if (Math.abs(d) < EPS) {
            return null;
        }
        final double aSq = (double) a.x() * a.x() + (double) a.y() * a.y();
        final double bSq = (double) b.x() * b.x() + (double) b.y() * b.y();
        final double cSq = (double) c.x() * c.x() + (double) c.y() * c.y();
        final double ux = (aSq * (b.y() - c.y()) + bSq * (c.y() - a.y()) + cSq * (a.y() - b.y())) / d;
        final double uy = (aSq * (c.x() - b.x()) + bSq * (a.x() - c.x()) + cSq * (b.x() - a.x())) / d;
        return new double[] {ux, uy};
    }

    private static double[] barycentric(
            final Point2 a, final Point2 b, final Point2 c, final double px, final double py) {
        final double det = (double) (b.y() - c.y()) * (a.x() - c.x()) + (double) (c.x() - b.x()) * (a.y() - c.y());
        if (Math.abs(det) < EPS) {
            return new double[] {1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
        }
        final double l1 = ((double) (b.y() - c.y()) * (px - c.x()) + (double) (c.x() - b.x()) * (py - c.y())) / det;
        final double l2 = ((double) (c.y() - a.y()) * (px - c.x()) + (double) (a.x() - c.x()) * (py - c.y())) / det;
        final double l3 = 1.0 - l1 - l2;
        return new double[] {l1, l2, l3};
    }

    private static long edgeKey(final int a, final int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static void linkEdge(
            final Map<Long, List<Integer>> edgeToTris, final int a, final int b, final int triIdx) {
        edgeToTris.computeIfAbsent(edgeKey(a, b), k -> new ArrayList<>(2)).add(triIdx);
    }

    private static void unlinkEdge(
            final Map<Long, List<Integer>> edgeToTris, final int a, final int b, final int triIdx) {
        final List<Integer> list = edgeToTris.get(edgeKey(a, b));
        if (list != null) {
            list.remove(Integer.valueOf(triIdx));
            if (list.isEmpty()) {
                edgeToTris.remove(edgeKey(a, b));
            }
        }
    }

    private static void linkTriangleEdges(final Map<Long, List<Integer>> edgeToTris, final Triangle tri) {
        linkEdge(edgeToTris, tri.a, tri.b, tri.id);
        linkEdge(edgeToTris, tri.b, tri.c, tri.id);
        linkEdge(edgeToTris, tri.c, tri.a, tri.id);
    }

    private static void unlinkTriangleEdges(final Map<Long, List<Integer>> edgeToTris, final Triangle tri) {
        unlinkEdge(edgeToTris, tri.a, tri.b, tri.id);
        unlinkEdge(edgeToTris, tri.b, tri.c, tri.id);
        unlinkEdge(edgeToTris, tri.c, tri.a, tri.id);
    }

    private static final class Triangle {
        final int id;
        final int a;
        final int b;
        final int c;
        final double area;
        final double aspectRatio;
        boolean active = true;

        Triangle(final int id, final int a, final int b, final int c, final List<Point2> uvs) {
            this.id = id;
            // Ensure CCW winding.
            final Point2 pa = uvs.get(a);
            final Point2 pb = uvs.get(b);
            final Point2 pc = uvs.get(c);
            final double cp = cross(pa, pb, pc);
            if (cp < 0) {
                this.a = a;
                this.b = c;
                this.c = b;
            } else {
                this.a = a;
                this.b = b;
                this.c = c;
            }
            this.area = Math.abs(cp) * 0.5;

            final Point2 v1 = uvs.get(this.a);
            final Point2 v2 = uvs.get(this.b);
            final Point2 v3 = uvs.get(this.c);
            final double l1 = Math.hypot(v2.x() - v1.x(), v2.y() - v1.y());
            final double l2 = Math.hypot(v3.x() - v2.x(), v3.y() - v2.y());
            final double l3 = Math.hypot(v1.x() - v3.x(), v1.y() - v3.y());
            final double minL = Math.max(1.0e-9, Math.min(l1, Math.min(l2, l3)));
            final double circumR = (l1 * l2 * l3) / Math.max(1.0e-9, 4.0 * this.area);
            this.aspectRatio = circumR / minL;
        }

        int oppositeVertex(final int u, final int v) {
            if ((u == a && v == b) || (u == b && v == a)) return c;
            if ((u == b && v == c) || (u == c && v == b)) return a;
            if ((u == c && v == a) || (u == a && v == c)) return b;
            return -1;
        }
    }

    private static final class Candidate {
        final int triangleIndex;
        final double area;
        final double aspectRatio;

        Candidate(final int triangleIndex, final double area, final double aspectRatio) {
            this.triangleIndex = triangleIndex;
            this.area = area;
            this.aspectRatio = aspectRatio;
        }
    }
}
