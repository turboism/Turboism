package dev.turboism.plugin.turbomesh.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurboMeshDomainTest {

    // ---------- helpers ----------

    /** A square mesh: 4 corners, 2 triangles, uv == position for easy assertions. */
    private static ArtMeshGeometry squareGeometry() {
        final List<Point2> corners =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(corners, corners, List.of(0, 1, 2, 0, 2, 3));
    }

    /** Square frame with a centered square hole (8 verts, 8 triangles). */
    private static ArtMeshGeometry holedGeometry() {
        final List<Point2> uv = new ArrayList<>(List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 1f),
                new Point2(0f, 1f),
                new Point2(0.4f, 0.4f),
                new Point2(0.6f, 0.4f),
                new Point2(0.6f, 0.6f),
                new Point2(0.4f, 0.6f)));
        // Ring of triangles around the hole: outer quad 0-1-2-3, inner quad 4-5-6-7.
        final List<Integer> idx = List.of(
                0,
                1,
                5,
                0,
                5,
                4, // bottom band
                1,
                2,
                6,
                1,
                6,
                5, // right band
                2,
                3,
                7,
                2,
                7,
                6, // top band
                3,
                0,
                4,
                3,
                4,
                7 // left band
                );
        return new ArtMeshGeometry(uv, uv, idx);
    }

    /** A "barbell": two lobes joined by a narrow waist, as a triangulated grid. */
    private static ArtMeshGeometry barbellGeometry() {
        // 24 columns x 8 rows of vertices; cells solid only inside the barbell silhouette.
        final int cols = 24;
        final int rows = 8;
        final List<Point2> uv = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        final boolean[][] solidCell = new boolean[cols - 1][rows - 1];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                uv.add(new Point2(x / (float) (cols - 1), y / (float) (rows - 1)));
            }
        }
        for (int y = 0; y < rows - 1; y++) {
            for (int x = 0; x < cols - 1; x++) {
                final boolean lobe = x < 8 || x >= 15;
                final boolean waist = !lobe && y >= 3 && y <= 4;
                solidCell[x][y] = lobe || waist;
                if (!solidCell[x][y]) {
                    continue;
                }
                final int a = y * cols + x;
                idx.add(a);
                idx.add(a + 1);
                idx.add(a + cols + 1);
                idx.add(a);
                idx.add(a + cols + 1);
                idx.add(a + cols);
            }
        }
        return new ArtMeshGeometry(uv, uv, idx);
    }

    private static long edgeCount(final List<Integer> idx, final int a, final int b) {
        long count = 0;
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            for (int e = 0; e < 3; e++) {
                final int x = idx.get(t + e);
                final int y = idx.get(t + (e + 1) % 3);
                if ((x == a && y == b) || (x == b && y == a)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<int[]> edgesOf(final List<Integer> idx) {
        final List<int[]> out = new ArrayList<>();
        for (int t = 0; t + 2 < idx.size(); t += 3) {
            for (int e = 0; e < 3; e++) {
                out.add(new int[] {idx.get(t + e), idx.get(t + (e + 1) % 3)});
            }
        }
        return out;
    }

    private static double orient(final Point2 a, final Point2 b, final Point2 c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    // ---------- PlacementSettings ----------

    @Test
    void settingsDefaultsAreSelfConsistent() {
        final PlacementSettings s = PlacementSettings.defaults();
        assertTrue(s.maxBoundarySpacingUv() > 0);
        assertTrue(s.interiorSpacingUv() > s.minSpacingUv());
        assertTrue(s.collinearTurnRadians() < s.tipTurnRadians());
        assertTrue(s.maxPoints() >= 8);
        assertTrue(s.minSpacingUv() > 0);
    }

    @Test
    void settingsRejectsBadValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlacementSettings(
                        -0.5,
                        0.2,
                        0.001,
                        0.001,
                        0.002,
                        0.05,
                        1.0,
                        2.0,
                        10,
                        3,
                        64,
                        100,
                        0L,
                        PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                        PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                        PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                        PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                        PlacementSettings.DEFAULT_EDGE_STITCH_CELLS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlacementSettings(
                        0.1,
                        0.0,
                        0.001,
                        0.001,
                        0.002,
                        0.05,
                        1.0,
                        2.0,
                        10,
                        3,
                        64,
                        100,
                        0L,
                        PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                        PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                        PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                        PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                        PlacementSettings.DEFAULT_EDGE_STITCH_CELLS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlacementSettings(
                        0.1,
                        0.2,
                        0.001,
                        0.001,
                        0.002,
                        0.05,
                        1.0,
                        2.0,
                        10,
                        3,
                        64,
                        4,
                        0L,
                        PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                        PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                        PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                        PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                        PlacementSettings.DEFAULT_EDGE_STITCH_CELLS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlacementSettings(
                        0.1,
                        0.2,
                        0.001,
                        0.001,
                        0.002,
                        1.5,
                        1.0,
                        2.0,
                        10,
                        3,
                        64,
                        100,
                        0L,
                        PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                        PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                        PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                        PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                        PlacementSettings.DEFAULT_EDGE_STITCH_CELLS));
    }

    // ---------- MeshTopology ----------

    @Test
    void boundaryLoopsFindsSquareRing() {
        final List<int[]> loops = MeshTopology.boundaryLoops(List.of(0, 1, 2, 0, 2, 3));
        assertEquals(1, loops.size());
        assertEquals(4, loops.get(0).length);
    }

    @Test
    void boundaryLoopsFindsInnerAndOuterRing() {
        final List<int[]> loops = MeshTopology.boundaryLoops(holedGeometry().triangleIndices());
        assertEquals(2, loops.size());
        assertEquals(4, loops.get(0).length + loops.get(1).length - 4); // both rings have 4 verts
    }

    @Test
    void boundaryLoopsEmptyForNoBoundary() {
        assertTrue(MeshTopology.boundaryLoops(List.of()).isEmpty());
    }

    // ---------- DomainMask ----------

    @Test
    void rasterizedMaskMatchesTriangleFootprint() {
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        assertTrue(mask.solidAtUv(0.5, 0.5));
        assertFalse(mask.solidAtUv(-0.05, 0.5));
        assertFalse(mask.solidAtUv(0.5, 1.1));
        assertTrue(mask.distanceToEmptyUv(0.5, 0.5) > 0.2);
        assertEquals(0.0, mask.distanceToEmptyUv(0.5, 2.0));
    }

    // ---------- RegionSegmenter ----------

    @Test
    void segmenterKeepsSolidBlobAsOneRegion() {
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final RegionSegmenter.Segmentation seg = RegionSegmenter.segment(mask, PlacementSettings.defaults());
        assertEquals(1, seg.regions().size());
        assertTrue(seg.separators().isEmpty());
    }

    @Test
    void segmenterSplitsBarbellAtWaist() {
        final ArtMeshGeometry g = barbellGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final PlacementSettings settings = new PlacementSettings(
                0.1,
                0.2,
                0.004,
                0.006,
                0.0025,
                Math.toRadians(3.0),
                Math.toRadians(55.0),
                3.0,
                24,
                6,
                64,
                2048,
                7L,
                PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                PlacementSettings.DEFAULT_EDGE_STITCH_CELLS);
        final RegionSegmenter.Segmentation seg = RegionSegmenter.segment(mask, settings);
        assertTrue(seg.regions().size() >= 2, "barbell should split at the waist");
        assertFalse(seg.separators().isEmpty(), "a split must emit separator polylines");
    }

    // ---------- BoundarySampler ----------

    @Test
    void boundarySamplerRespectsArcCap() {
        final List<Point2> ring =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        final BoundarySampler.SampledRing sampled =
                BoundarySampler.resample(ring, 0.15, PlacementSettings.defaults(), null);
        // Arc-cap bisection halves each unit edge down to 0.125 <= 0.15: 8 per edge, 32 total.
        assertTrue(sampled.ring().size() >= 28, "perimeter 4 at cap 0.15 yields ~32 samples");
        assertTrue(sampled.ring().size() <= 40);
    }

    @Test
    void boundarySamplerDensifiesSharpTips() {
        // Diamond with a very sharp spike at the right vertex.
        final List<Point2> ring =
                List.of(new Point2(0f, 0.5f), new Point2(0.4f, 0.55f), new Point2(1.0f, 0.5f), new Point2(0.4f, 0.45f));
        final PlacementSettings settings = new PlacementSettings(
                0.1,
                0.2,
                0.004,
                0.006,
                0.0025,
                Math.toRadians(3.0),
                Math.toRadians(40.0),
                3.0,
                24,
                6,
                64,
                2048,
                7L,
                PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                PlacementSettings.DEFAULT_EDGE_STITCH_CELLS);
        final BoundarySampler.SampledRing sampled = BoundarySampler.resample(ring, 0.1, settings, null);
        assertFalse(sampled.interiorAnchors().isEmpty(), "sharp spike must emit an interior anchor");
        // Samples cluster near the spike (x≈1.0).
        final long near = sampled.ring().stream().filter(p -> p.x() > 0.9).count();
        assertTrue(near >= 3, "tip region should be sampled densely");
    }

    @Test
    void anchorsLandInsideConvexCorners() {
        // Unit square corners all exceed the tip threshold; with a mask, every emitted anchor
        // must sit strictly inside the silhouette — never outside, hugging a corner.
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final BoundarySampler.SampledRing sampled =
                BoundarySampler.resample(g.uvs(), 0.15, PlacementSettings.defaults(), mask);
        assertEquals(4, sampled.interiorAnchors().size(), "each square corner earns one anchor");
        for (final Point2 a : sampled.interiorAnchors()) {
            assertTrue(
                    a.x() > 0.0 && a.x() < 1.0 && a.y() > 0.0 && a.y() < 1.0,
                    "anchor must be strictly inside the square, got " + a);
            assertTrue(mask.solidAtUv(a.x(), a.y()));
        }
    }

    @Test
    void anchorsLandInsideForClockwiseRing() {
        // Same square walked CW: mask probing must still resolve the material side.
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final List<Point2> cw = List.of(new Point2(0f, 0f), new Point2(0f, 1f), new Point2(1f, 1f), new Point2(1f, 0f));
        final BoundarySampler.SampledRing sampled =
                BoundarySampler.resample(cw, 0.15, PlacementSettings.defaults(), mask);
        assertEquals(4, sampled.interiorAnchors().size());
        for (final Point2 a : sampled.interiorAnchors()) {
            assertTrue(
                    a.x() > 0.0 && a.x() < 1.0 && a.y() > 0.0 && a.y() < 1.0,
                    "anchor must be strictly inside the square, got " + a);
        }
    }

    @Test
    void anchorAtReflexVertexStaysInMaterial() {
        // L-shape (top-right quadrant missing, CCW). The notch vertex is reflex: its anchor
        // must land inside the material (south-west of the notch), not in the void.
        final List<Point2> ring = List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 0.5f),
                new Point2(0.5f, 0.5f),
                new Point2(0.5f, 1f),
                new Point2(0f, 1f));
        final List<Integer> idx = List.of(0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 5);
        final DomainMask mask = DomainMask.rasterize(ring, idx, 64);
        final PlacementSettings settings = new PlacementSettings(
                0.2,
                0.3,
                0.004,
                0.006,
                0.0025,
                Math.toRadians(3.0),
                Math.toRadians(55.0),
                3.0,
                24,
                6,
                64,
                2048,
                7L,
                PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                PlacementSettings.DEFAULT_EDGE_STITCH_CELLS);
        final BoundarySampler.SampledRing sampled = BoundarySampler.resample(ring, 0.2, settings, mask);
        for (final Point2 a : sampled.interiorAnchors()) {
            assertTrue(mask.solidAtUv(a.x(), a.y()), "anchor must stay inside material: " + a);
        }
    }

    // ---------- InteriorSampler ----------

    @Test
    void interiorSamplerStaysInsideRegionWithMargin() {
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final RegionSegmenter.Segmentation seg = RegionSegmenter.segment(mask, PlacementSettings.defaults());
        final RegionSegmenter.Region region = seg.regions().get(0);
        final List<Point2> pts = InteriorSampler.sample(mask, region, 0.1, PlacementSettings.defaults());
        assertFalse(pts.isEmpty());
        for (final Point2 p : pts) {
            assertTrue(mask.solidAtUv(p.x(), p.y()), "sample must be inside the mask");
            assertTrue(
                    mask.distanceToEmptyUv(p.x(), p.y())
                            >= PlacementSettings.defaults().interiorMarginUv() * 0.5,
                    "sample keeps protection margin");
        }
    }

    @Test
    void interiorSamplerIsDeterministic() {
        final ArtMeshGeometry g = squareGeometry();
        final DomainMask mask = DomainMask.rasterize(g.uvs(), g.triangleIndices(), 64);
        final RegionSegmenter.Segmentation seg = RegionSegmenter.segment(mask, PlacementSettings.defaults());
        final List<Point2> a = InteriorSampler.sample(mask, seg.regions().get(0), 0.1, PlacementSettings.defaults());
        final List<Point2> b = InteriorSampler.sample(mask, seg.regions().get(0), 0.1, PlacementSettings.defaults());
        assertEquals(a, b);
    }

    // ---------- ConstraintDelaunay ----------

    @Test
    void delaunayOfSquareGivesTwoTriangles() {
        final List<Point2> pts =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        final ConstraintDelaunay.Result r = ConstraintDelaunay.triangulate(pts, List.of(), null);
        assertEquals(6, r.triangleIndices().size());
    }

    @Test
    void constrainedEdgeAppearsInOutput() {
        // Force the "wrong" diagonal: points arranged so Delaunay picks (0,2) but we demand (1,3).
        final List<Point2> pts =
                List.of(new Point2(0f, 0f), new Point2(0.6f, 0.4f), new Point2(1f, 1f), new Point2(0.4f, 0.6f));
        final ConstraintDelaunay.Result r = ConstraintDelaunay.triangulate(pts, List.of(new int[] {1, 3}), null);
        assertTrue(edgeCount(r.triangleIndices(), 1, 3) >= 1, "constraint edge must exist");
    }

    @Test
    void allTrianglesAreCcwAndInRange() {
        final List<Point2> pts = new ArrayList<>();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                pts.add(new Point2(x / 5f, y / 5f));
            }
        }
        final ConstraintDelaunay.Result r = ConstraintDelaunay.triangulate(pts, List.of(), null);
        for (int i = 0; i < r.triangleIndices().size(); i++) {
            final int idx = r.triangleIndices().get(i);
            assertTrue(idx >= 0 && idx < r.points().size());
        }
        for (int t = 0; t + 2 < r.triangleIndices().size(); t += 3) {
            final Point2 a = r.points().get(r.triangleIndices().get(t));
            final Point2 b = r.points().get(r.triangleIndices().get(t + 1));
            final Point2 c = r.points().get(r.triangleIndices().get(t + 2));
            assertTrue(orient(a, b, c) > 0, "triangle must be counter-clockwise");
        }
    }

    @Test
    void collinearAndDuplicateInputsDoNotCrash() {
        final List<Point2> pts = List.of(
                new Point2(0f, 0f),
                new Point2(0.5f, 0f),
                new Point2(1f, 0f),
                new Point2(0.5f, 0.5f),
                new Point2(0.5f, 0f)); // duplicate of index 1
        final ConstraintDelaunay.Result r = ConstraintDelaunay.triangulate(pts, List.of(), null);
        for (int i = 0; i < r.triangleIndices().size(); i++) {
            assertTrue(r.triangleIndices().get(i) < r.points().size());
        }
    }

    // ---------- TurboMeshPlanner (end to end) ----------

    @Test
    void plannerProducesValidGeometryForSquare() throws TurboMeshPlanner.PlacementException {
        final TurboMeshPlanner.PlacementPlan plan =
                TurboMeshPlanner.plan(squareGeometry(), PlacementSettings.defaults());
        final ArtMeshGeometry g = plan.geometry();
        assertEquals(g.positions().size(), g.uvs().size());
        assertEquals(0, g.triangleIndices().size() % 3);
        assertFalse(g.triangleIndices().isEmpty());
        for (final int idx : g.triangleIndices()) {
            assertTrue(idx >= 0 && idx < g.positions().size());
        }
        // Boundary stays near the unit square silhouette.
        for (final Point2 uv : g.uvs()) {
            assertTrue(uv.x() >= -0.01 && uv.x() <= 1.01);
            assertTrue(uv.y() >= -0.01 && uv.y() <= 1.01);
        }
    }

    @Test
    void plannerSurvivesWhenAlphaMaskIsSmallerThanRing() throws TurboMeshPlanner.PlacementException {
        // Alpha silhouette covers only a quarter of the quad: the source ring sits mostly on
        // transparent cells. Triangulation filtering must still keep every ring edge.
        final ArtMeshGeometry g = squareGeometry();
        final int size = 16;
        final int[] argb = new int[size * size];
        for (int y = 0; y < size / 2; y++) {
            for (int x = 0; x < size / 2; x++) {
                argb[y * size + x] = 0xFF000000;
            }
        }
        final DomainMask mask = DomainMask.fromPixels(argb, size, size, -0.05, -0.05, 1.05, 1.05, 64, 8);
        final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(
                g,
                mask,
                new PlacementSettings(
                        0.1,
                        0.15,
                        0.004,
                        0.006,
                        0.0025,
                        Math.toRadians(3.0),
                        Math.toRadians(55.0),
                        3.0,
                        16,
                        6,
                        64,
                        2048,
                        3L,
                        PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                        PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                        PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                        PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                        PlacementSettings.DEFAULT_EDGE_STITCH_CELLS));
        assertFalse(plan.geometry().triangleIndices().isEmpty());
    }

    @Test
    void plannerLeavesHoleEmpty() throws TurboMeshPlanner.PlacementException {
        final PlacementSettings settings = new PlacementSettings(
                0.1,
                0.15,
                0.004,
                0.006,
                0.0025,
                Math.toRadians(3.0),
                Math.toRadians(55.0),
                3.0,
                16,
                6,
                64,
                2048,
                3L,
                PlacementSettings.DEFAULT_EDGE_MIN_CONTRAST,
                PlacementSettings.DEFAULT_EDGE_COLOR_DISTANCE,
                PlacementSettings.DEFAULT_EDGE_MIN_LENGTH_UV,
                PlacementSettings.DEFAULT_EDGE_DP_TOLERANCE_UV,
                PlacementSettings.DEFAULT_EDGE_STITCH_CELLS);
        final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(holedGeometry(), settings);
        final ArtMeshGeometry g = plan.geometry();
        for (int t = 0; t + 2 < g.triangleIndices().size(); t += 3) {
            final Point2 a = g.uvs().get(g.triangleIndices().get(t));
            final Point2 b = g.uvs().get(g.triangleIndices().get(t + 1));
            final Point2 c = g.uvs().get(g.triangleIndices().get(t + 2));
            final double cu = (a.x() + b.x() + c.x()) / 3.0;
            final double cv = (a.y() + b.y() + c.y()) / 3.0;
            final boolean insideHole = cu > 0.46 && cu < 0.54 && cv > 0.46 && cv < 0.54;
            assertFalse(insideHole, "no triangle centroid may land inside the hole");
        }
    }

    @Test
    void plannerIsDeterministicPerSeed() throws TurboMeshPlanner.PlacementException {
        final PlacementSettings s = PlacementSettings.defaults();
        final TurboMeshPlanner.PlacementPlan a = TurboMeshPlanner.plan(barbellGeometry(), s);
        final TurboMeshPlanner.PlacementPlan b = TurboMeshPlanner.plan(barbellGeometry(), s);
        assertEquals(a.geometry().uvs(), b.geometry().uvs());
        assertEquals(a.geometry().triangleIndices(), b.geometry().triangleIndices());
    }

    @Test
    void plannerRejectsEmptySource() {
        final ArtMeshGeometry empty = new ArtMeshGeometry(List.of(), List.of(), List.of());
        assertThrows(
                TurboMeshPlanner.PlacementException.class,
                () -> TurboMeshPlanner.plan(empty, PlacementSettings.defaults()));
    }

    // ---------- UvToPosition ----------

    @Test
    void barycentricMappingMatchesSourceTriangle() {
        final List<Point2> uv = List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(0f, 1f));
        final List<Point2> pos = List.of(new Point2(10f, 0f), new Point2(0f, 10f), new Point2(-10f, -10f));
        final UvToPosition map = UvToPosition.of(uv, pos, List.of(0, 1, 2));
        final Point2 at = map.map(0.25, 0.25); // w0=0.5 w1=0.25 w2=0.25
        assertEquals(0.5 * 10 + 0.25 * 0 + 0.25 * -10, at.x(), 1.0e-3);
        assertEquals(0.5 * 0 + 0.25 * 10 + 0.25 * -10, at.y(), 1.0e-3);
    }

    @Test
    void affineFallbackMapsOutsidePoints() {
        final List<Point2> uv = List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(0f, 1f));
        final List<Point2> pos = List.of(new Point2(0f, 0f), new Point2(100f, 0f), new Point2(0f, 100f));
        final UvToPosition map = UvToPosition.of(uv, pos, List.of(0, 1, 2));
        final Point2 outside = map.map(2.0, -1.0); // outside the triangle -> affine
        assertTrue(Math.abs(outside.x() - 200f) < 1.0f || Math.abs(outside.y() + 100f) < 1.0f);
    }
}
