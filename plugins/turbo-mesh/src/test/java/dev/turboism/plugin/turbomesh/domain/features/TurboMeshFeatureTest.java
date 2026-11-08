package dev.turboism.plugin.turbomesh.domain.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.turbomesh.domain.DomainMask;
import dev.turboism.plugin.turbomesh.domain.PlacementSettings;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner.PlacementPlan;
import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder;
import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder.MeshConstraints;
import dev.turboism.plugin.turbomesh.domain.sampler.AdaptiveInteriorSampler;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.2.0 M1 acceptance: geometric feature layer — corners, holes, density, budget, determinism. */
class TurboMeshFeatureTest {

    // ---------- geometry fixtures (uv == position) ----------

    private static ArtMeshGeometry square() {
        final List<Point2> c = List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(c, c, List.of(0, 1, 2, 0, 2, 3));
    }

    /** L-shape: 6 corners including one reflex — all 90 degrees, sharp at any scale. */
    private static ArtMeshGeometry lShape() {
        final List<Point2> c = List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 0.5f),
                new Point2(0.5f, 0.5f),
                new Point2(0.5f, 1f),
                new Point2(0f, 1f));
        // Ear-clipped fan of the concave hexagon: 0-1-2, 0-2-3, 0-3-4, 0-4-5.
        return new ArtMeshGeometry(c, c, List.of(0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 5));
    }

    /** Annulus: outer quad with a centered square hole. */
    private static ArtMeshGeometry annulus() {
        final List<Point2> uv = new ArrayList<>(List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 1f),
                new Point2(0f, 1f),
                new Point2(0.4f, 0.4f),
                new Point2(0.6f, 0.4f),
                new Point2(0.6f, 0.6f),
                new Point2(0.4f, 0.6f)));
        final List<Integer> idx = new ArrayList<>();
        final int[] outer = {0, 1, 2, 3};
        final int[] inner = {4, 5, 6, 7};
        for (int i = 0; i < 4; i++) {
            final int a = outer[i];
            final int b = outer[(i + 1) % 4];
            final int c = inner[i];
            final int d = inner[(i + 1) % 4];
            idx.add(a);
            idx.add(b);
            idx.add(d);
            idx.add(a);
            idx.add(d);
            idx.add(c);
        }
        return new ArtMeshGeometry(uv, uv, idx);
    }

    /** Long thin strip — the narrow-region density boost should densify it. */
    private static ArtMeshGeometry strip() {
        final List<Point2> c =
                List.of(new Point2(0f, 0.45f), new Point2(1f, 0.45f), new Point2(1f, 0.55f), new Point2(0f, 0.55f));
        return new ArtMeshGeometry(c, c, List.of(0, 1, 2, 0, 2, 3));
    }

    // ---------- pixel-mask fixtures ----------

    /** ARGB raster with alpha 255 inside {@code (u,v) in [0,1]} grid predicate. */
    private interface Shape {
        boolean solid(double u, double v);
    }

    private static DomainMask pixelMask(final Shape shape) {
        final int size = 160;
        final int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final double u = (x + 0.5) / size;
                // fromPixels maps mask row y straight to pixel row y: v grows with the row.
                final double v = (y + 0.5) / size;
                argb[y * size + x] = shape.solid(u, v) ? 0xFF202020 : 0x00000000;
            }
        }
        // Identity mapping: uv [0,1]^2 spans the whole raster.
        return DomainMask.fromPixels(argb, size, size, 0.0, 0.0, 1.0, 1.0, 160, 8);
    }

    private static ArtMeshGeometry fullQuad() {
        return square();
    }

    /** Two blobs touching at one lattice point (diagonal channel), alpha-defined. */
    private static DomainMask diagonalContactMask() {
        return pixelMask((u, v) ->
                (u < 0.45 && v < 0.45 && u > 0.05 && v > 0.05) || (u > 0.55 && v > 0.55 && u < 0.95 && v < 0.95));
    }

    /** Big blob with a transparent square island inside. */
    private static DomainMask islandMask() {
        return pixelMask((u, v) -> {
            final boolean blob = u > 0.1 && u < 0.9 && v > 0.1 && v < 0.9;
            final boolean island = u > 0.4 && u < 0.6 && v > 0.4 && v < 0.6;
            return blob && !island;
        });
    }

    // ---------- tests ----------

    @Test
    void disabledPathMatchesLegacyExactly() throws Exception {
        final PlacementSettings settings = PlacementSettings.defaults();
        for (final ArtMeshGeometry geometry : List.of(square(), lShape(), annulus())) {
            final PlacementPlan legacy = TurboMeshPlanner.plan(geometry, settings);
            final PlacementPlan flagged = TurboMeshPlanner.plan(geometry, settings, FeatureOptions.disabled());
            assertEquals(legacy.geometry().uvs(), flagged.geometry().uvs(), "uv mismatch");
            assertEquals(legacy.geometry().triangleIndices(), flagged.geometry().triangleIndices());
            assertEquals(legacy.geometry().positions(), flagged.geometry().positions());
        }
    }

    @Test
    void cornersSurviveVerbatimOnLShape() throws Exception {
        final PlacementSettings settings = PlacementSettings.defaults();
        final ArtMeshGeometry source = lShape();
        final List<List<Point2>> rings = new ArrayList<>();
        rings.add(List.of(
                source.uvs().get(0),
                source.uvs().get(1),
                source.uvs().get(2),
                source.uvs().get(3),
                source.uvs().get(4),
                source.uvs().get(5)));
        final MeshFeatureAnalyzer.Analysis analysis = MeshFeatureAnalyzer.analyze(
                DomainMask.rasterize(source.uvs(), source.triangleIndices(), 256), rings, FeatureOptions.defaults());
        // NMS clusters adjacent sharp vertices; an L-shape yields at least every other corner.
        assertTrue(analysis.corners().get(0).size() >= 3, "expected L-shape corners detected");

        final PlacementPlan plan = TurboMeshPlanner.plan(source, settings, FeatureOptions.defaults());
        for (final MeshFeatureAnalyzer.Corner corner : analysis.corners().get(0)) {
            boolean present = false;
            for (final Point2 v : plan.geometry().uvs()) {
                if (v.x() == corner.at().x() && v.y() == corner.at().y()) {
                    present = true;
                    break;
                }
            }
            assertTrue(present, "corner " + corner.at() + " lost during re-placement");
        }
    }

    @Test
    void annulusHoleStaysUnfilled() throws Exception {
        final PlacementPlan plan =
                TurboMeshPlanner.plan(annulus(), PlacementSettings.defaults(), FeatureOptions.defaults());
        final List<Integer> idx = plan.geometry().triangleIndices();
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            final Point2 a = plan.geometry().uvs().get(idx.get(i));
            final Point2 b = plan.geometry().uvs().get(idx.get(i + 1));
            final Point2 c = plan.geometry().uvs().get(idx.get(i + 2));
            final double cu = (a.x() + b.x() + c.x()) / 3.0;
            final double cv = (a.y() + b.y() + c.y()) / 3.0;
            assertFalse(
                    cu > 0.42 && cu < 0.58 && cv > 0.42 && cv < 0.58, "triangle fills the hole at " + cu + "," + cv);
        }
    }

    @Test
    void noTriangleDeepInsideEmptyInterior() throws Exception {
        // Island fixture: the interior alpha hole must stay unfilled and ring-exempt triangles
        // must never sweep across its interior.
        final DomainMask island = islandMask();
        final PlacementPlan islandPlan =
                TurboMeshPlanner.plan(fullQuad(), island, PlacementSettings.defaults(), FeatureOptions.defaults());
        assertNoCentroidInside(islandPlan, 0.42, 0.58, 0.42, 0.58);
        // Diagonal fixture: no triangle may consist solely of alpha-empty vertices far from the
        // source ring (ring-covering fans with a solid vertex are legal by design).
        final DomainMask diagonal = diagonalContactMask();
        final PlacementPlan plan =
                TurboMeshPlanner.plan(fullQuad(), diagonal, PlacementSettings.defaults(), FeatureOptions.defaults());
        final List<Integer> idx = plan.geometry().triangleIndices();
        assertTrue(idx.size() / 3 > 4, "expected a real mesh");
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            final Point2 a = plan.geometry().uvs().get(idx.get(i));
            final Point2 b = plan.geometry().uvs().get(idx.get(i + 1));
            final Point2 c = plan.geometry().uvs().get(idx.get(i + 2));
            final boolean allEmpty = !diagonal.solidAtUv(a.x(), a.y())
                    && !diagonal.solidAtUv(b.x(), b.y())
                    && !diagonal.solidAtUv(c.x(), c.y());
            if (allEmpty) {
                final double cu = (a.x() + b.x() + c.x()) / 3.0;
                final double cv = (a.y() + b.y() + c.y()) / 3.0;
                final double ringBand = Math.min(Math.min(cu, 1.0 - cu), Math.min(cv, 1.0 - cv));
                assertFalse(ringBand > 0.15, "all-empty triangle deep inside the domain: " + cu + "," + cv);
            }
        }
    }

    private static void assertNoCentroidInside(
            final PlacementPlan plan, final double u0, final double u1, final double v0, final double v1) {
        final List<Integer> idx = plan.geometry().triangleIndices();
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            final Point2 a = plan.geometry().uvs().get(idx.get(i));
            final Point2 b = plan.geometry().uvs().get(idx.get(i + 1));
            final Point2 c = plan.geometry().uvs().get(idx.get(i + 2));
            final double cu = (a.x() + b.x() + c.x()) / 3.0;
            final double cv = (a.y() + b.y() + c.y()) / 3.0;
            assertFalse(
                    cu > u0 && cu < u1 && cv > v0 && cv < v1,
                    "triangle centroid inside the protected interior: " + cu + "," + cv);
        }
    }

    @Test
    void adaptiveSamplerRespectsBudgetAndSpacing() {
        final DomainMask mask = DomainMask.rasterize(square().uvs(), square().triangleIndices(), 256);
        final PlacementSettings settings = PlacementSettings.defaults();
        final FeatureOptions options = FeatureOptions.defaults();
        final MeshFeatureAnalyzer.Analysis analysis =
                MeshFeatureAnalyzer.analyze(mask, List.of(square().uvs()), options);
        final MeshConstraints constraints = MeshConstraintBuilder.build(List.of(square().uvs()), List.of());
        final FeatureField field = new GeometryFeatureField(analysis, constraints.hardEdges());
        final List<dev.turboism.plugin.turbomesh.domain.RegionSegmenter.Region> regions =
                dev.turboism.plugin.turbomesh.domain.RegionSegmenter.segment(mask, settings)
                        .regions();

        final List<Point2> few = AdaptiveInteriorSampler.sample(mask, regions, field, settings, options, 10);
        assertTrue(few.size() <= 10, "budget exceeded: " + few.size());
        assertTrue(few.size() >= 3, "too few points: " + few.size());

        final List<Point2> many = AdaptiveInteriorSampler.sample(mask, regions, field, settings, options, 100000);
        final double rMin =
                Math.max(settings.minSpacingUv() * 1.5, settings.interiorSpacingUv() * options.minRadiusFactor());
        for (int i = 0; i < few.size(); i++) {
            for (int j = i + 1; j < few.size(); j++) {
                final double d = Math.hypot(
                        few.get(i).x() - few.get(j).x(),
                        few.get(i).y() - few.get(j).y());
                assertTrue(d >= 0.35 * rMin, "pair " + i + "-" + j + " too close: " + d);
            }
        }
    }

    @Test
    void adaptiveSamplerIsDeterministic() {
        final DomainMask mask = pixelMask((u, v) -> u > 0.15 && u < 0.85 && v > 0.15 && v < 0.85);
        final PlacementSettings settings = PlacementSettings.defaults();
        final FeatureOptions options = FeatureOptions.defaults();
        final List<dev.turboism.plugin.turbomesh.domain.RegionSegmenter.Region> regions =
                dev.turboism.plugin.turbomesh.domain.RegionSegmenter.segment(mask, settings)
                        .regions();
        final MeshFeatureAnalyzer.Analysis analysis =
                MeshFeatureAnalyzer.analyze(mask, List.of(square().uvs()), options);
        final FeatureField field = new GeometryFeatureField(analysis, List.of());
        final List<Point2> first = AdaptiveInteriorSampler.sample(mask, regions, field, settings, options, 500);
        final List<Point2> second = AdaptiveInteriorSampler.sample(mask, regions, field, settings, options, 500);
        assertEquals(first, second, "same seed must produce the same points");
    }

    @Test
    void narrowStripGainsInteriorPoints() throws Exception {
        final PlacementPlan plan =
                TurboMeshPlanner.plan(strip(), PlacementSettings.defaults(), FeatureOptions.defaults());
        // The 0.1-wide strip must end up with a real mesh, denser than 2 boundary rows only.
        assertTrue(
                plan.geometry().uvs().size() >= 8,
                "strip too sparse: " + plan.geometry().uvs().size());
        assertTrue(plan.stats().interiorPoints() >= 2, "no interior fill on the strip: " + plan.stats());
    }

    @Test
    void noisyBoundaryIsCoarsenedBeforeRefinement() throws Exception {
        // Square ring polluted by 8 vertices per edge with +-0.004 jitter (auto-meshed look).
        final java.util.List<Point2> noisy = new ArrayList<>();
        final java.util.Random r = new java.util.Random(7);
        final int per = 8;
        for (int e = 0; e < 4; e++) {
            for (int i = 0; i < per; i++) {
                final float t = i / (float) per;
                float u = 0f;
                float v = 0f;
                switch (e) {
                    case 0 -> {
                        u = t;
                        v = 0f;
                    }
                    case 1 -> {
                        u = 1f;
                        v = t;
                    }
                    case 2 -> {
                        u = 1f - t;
                        v = 1f;
                    }
                    default -> {
                        u = 0f;
                        v = 1f - t;
                    }
                }
                noisy.add(new Point2(
                        u + (float) ((r.nextDouble() - 0.5) * 0.008), v + (float) ((r.nextDouble() - 0.5) * 0.008)));
            }
        }
        final ArtMeshGeometry noisyMesh = new ArtMeshGeometry(noisy, noisy, fanTriangles(noisy.size()));
        final PlacementSettings settings = PlacementSettings.defaults();
        final PlacementPlan legacy = TurboMeshPlanner.plan(noisyMesh, settings);
        final PlacementPlan feature = TurboMeshPlanner.plan(noisyMesh, settings, FeatureOptions.defaults());
        assertTrue(
                feature.stats().boundaryPoints() < legacy.stats().boundaryPoints(),
                () -> "coarse-first should emit fewer boundary points: " + feature.stats() + " vs legacy "
                        + legacy.stats());
        final List<List<Point2>> rings = List.of(noisy);
        final DomainMask mask = DomainMask.rasterize(noisy, fanTriangles(noisy.size()), settings.rasterMaxDim());
        final MeshFeatureAnalyzer.Analysis analysis =
                MeshFeatureAnalyzer.analyze(mask, rings, FeatureOptions.defaults());
        // The detected corners must survive verbatim.
        for (final MeshFeatureAnalyzer.Corner corner : analysis.corners().get(0)) {
            boolean present = feature.geometry().uvs().stream()
                    .anyMatch(v ->
                            v.x() == corner.at().x() && v.y() == corner.at().y());
            assertTrue(present, "corner " + corner.at() + " lost by coarsening");
        }
    }

    @Test
    void rigidPresetNeverTearsAlongArtworkLines() throws Exception {
        final DomainMask metal = pixelMask((u, v) -> {
            final boolean plate = u > 0.15 && u < 0.85 && v > 0.15 && v < 0.85;
            final boolean screw = Math.hypot(u - 0.25, v - 0.25) < 0.04
                    || Math.hypot(u - 0.75, v - 0.25) < 0.04
                    || Math.hypot(u - 0.25, v - 0.75) < 0.04
                    || Math.hypot(u - 0.75, v - 0.75) < 0.04;
            return plate && !screw;
        });
        final PlacementPlan plan = TurboMeshPlanner.plan(
                fullQuad(),
                metal,
                PlacementSettings.defaults(),
                FeatureOptions.forPreset(DensityPreset.RIGID_GEOMETRY));
        assertEquals(0, plan.stats().separatorEdges(), "rigid preset must not cut along artwork lines");
    }

    private static List<Integer> fanTriangles(final int n) {
        final List<Integer> idx = new ArrayList<>();
        for (int i = 1; i + 1 < n; i++) {
            idx.add(0);
            idx.add(i);
            idx.add(i + 1);
        }
        return idx;
    }

    @Test
    void diagnosticsSvgRenders() throws Exception {
        final PlacementSettings settings = PlacementSettings.defaults();
        final ArtMeshGeometry source = lShape();
        final DomainMask mask = DomainMask.rasterize(source.uvs(), source.triangleIndices(), 256);
        final FeatureOptions options = FeatureOptions.defaults();
        final List<List<Point2>> rings = List.of(source.uvs());
        final MeshFeatureAnalyzer.Analysis analysis = MeshFeatureAnalyzer.analyze(mask, rings, options);
        final List<MeshFeatureAnalyzer.Corner> corners = new ArrayList<>();
        for (final List<MeshFeatureAnalyzer.Corner> c : analysis.corners()) {
            corners.addAll(c);
        }
        final MeshConstraints constraints = MeshConstraintBuilder.build(rings, corners);
        final FeatureField field = new GeometryFeatureField(analysis, constraints.hardEdges());
        final PlacementPlan plan = TurboMeshPlanner.plan(source, settings, options);
        final String svg = FeatureDiagnostics.svg(plan.geometry(), field, constraints, corners, 512);
        assertTrue(svg.startsWith("<?xml"));
        assertTrue(svg.contains("#e03030"), "corner markers missing");
        assertTrue(svg.contains("polygon"), "triangles missing");
    }
}
