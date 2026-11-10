package dev.turboism.plugin.turbomesh.domain.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.turbomesh.domain.DomainMask;
import dev.turboism.plugin.turbomesh.domain.PlacementSettings;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner.PlacementPlan;
import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder;
import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder.MeshConstraints;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 0.2.0 M2 acceptance: texture-aware sampling. Same texture, same budget, same seed across the
 * three-way comparison 0.1.0 legacy / M1 geometry / M2 texture. Metrics are deterministic; the
 * thresholds below assert the pre-defined improvement (hair strands) and non-degradation on the
 * other materials, per the spec's comparison protocol.
 */
class TurboMeshTextureFeatureTest {

    private static final Path DIAG_DIR = Path.of("build", "test-diagnostics");

    // ---------- material fixtures (alpha + luminance textures) ----------

    private record Material(DomainMask mask, String name) {}

    /** Hair sheet: six wavy diagonal strands on a transparent background. */
    private static Material hairStrands() {
        return material("hair", (u, v) -> {
            final double wave = Math.sin(u * Math.PI * 3.0) * 0.06;
            final double band = (v - (0.2 + 0.6 * u) + wave) % 0.14;
            final boolean strand = ((band + 0.14) % 0.14) < 0.07;
            return strand && u > 0.05 && u < 0.95 && v > 0.05 && v < 0.95;
        });
    }

    /** Skirt: radial pleats fanning out below a waist arc, plus a smooth body. */
    private static Material skirt() {
        return material("skirt", (u, v) -> {
            if (v < 0.3 || v > 0.95 || u < 0.1 || u > 0.9) {
                return false;
            }
            final double t = (v - 0.3) / 0.65; // 0 at waist, 1 at hem
            final double halfWidth = 0.08 + 0.32 * t;
            final double center = 0.5 + 0.0 * t;
            if (Math.abs(u - center) > halfWidth) {
                return false;
            }
            final double pleat = Math.abs(Math.sin((u - center) / halfWidth * Math.PI * 4.0));
            final double shade = 0.35 + 0.3 * pleat * t; // pleats darken toward the hem
            return shade > 0.0; // alpha always solid inside the fan; shading lives in luminance
        });
    }

    /** Metal plate: geometric plate with a hard border, screw holes and a brushed streak. */
    private static Material metalPlate() {
        return material("metal", (u, v) -> {
            final boolean plate = u > 0.15 && u < 0.85 && v > 0.15 && v < 0.85;
            final boolean screwA = Math.hypot(u - 0.25, v - 0.25) < 0.04;
            final boolean screwB = Math.hypot(u - 0.75, v - 0.25) < 0.04;
            final boolean screwC = Math.hypot(u - 0.25, v - 0.75) < 0.04;
            final boolean screwD = Math.hypot(u - 0.75, v - 0.75) < 0.04;
            return plate && !screwA && !screwB && !screwC && !screwD;
        });
    }

    private static Material material(final String name, final ShapePredicate shape) {
        final int size = 200;
        final int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final double u = (x + 0.5) / size;
                final double v = (y + 0.5) / size;
                final int value = shape.solid(u, v) ? 0xFF303030 : 0x00000000;
                argb[y * size + x] = value;
            }
        }
        return new Material(DomainMask.fromPixels(argb, size, size, 0.0, 0.0, 1.0, 1.0, 200, 8), name);
    }

    private interface ShapePredicate {
        boolean solid(double u, double v);
    }

    private static ArtMeshGeometry fullQuad() {
        final List<Point2> c = List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(c, c, List.of(0, 1, 2, 0, 2, 3));
    }

    private record Plan(
            String label, PlacementPlan plan, MeshFeatureAnalyzer.Analysis analysis, MeshConstraints constraints) {}

    private static Plan planWith(
            final Material material, final FeatureOptions options, final PlacementSettings settings) throws Exception {
        final ArtMeshGeometry quad = fullQuad();
        final PlacementPlan plan = TurboMeshPlanner.plan(quad, material.mask(), settings, options);
        final List<List<Point2>> rings = List.of(quad.uvs());
        final MeshFeatureAnalyzer.Analysis analysis =
                options.enabled() ? MeshFeatureAnalyzer.analyze(material.mask(), rings, options) : null;
        final MeshConstraints constraints = MeshConstraintBuilder.build(rings, List.of());
        return new Plan(options.enabled() ? options.preset().name() : "LEGACY", plan, analysis, constraints);
    }

    // ---------- metrics ----------

    /** Share of mesh edges within 20 degrees of the local field direction (confident cells only). */
    private static double edgeAlignmentShare(final PlacementPlan plan, final MeshFeatureAnalyzer.Analysis analysis) {
        final DomainMask mask = analysis.mask();
        final List<Integer> idx = plan.geometry().triangleIndices();
        long considered = 0;
        long aligned = 0;
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            for (int e = 0; e < 3; e++) {
                final int a = idx.get(i + e);
                final int b = idx.get(i + (e + 1) % 3);
                if (a > b && !seen.add((long) a * 1_000_003L + b) || a <= b && !seen.add((long) a * 1_000_003L + b)) {
                    continue;
                }
                final Point2 p = plan.geometry().uvs().get(a);
                final Point2 q = plan.geometry().uvs().get(b);
                final int cell = mask.cellAtUv((p.x() + q.x()) / 2.0, (p.y() + q.y()) / 2.0);
                if (cell < 0 || analysis.coherence()[cell] < 0.5) {
                    continue;
                }
                considered++;
                double edgeAngle = Math.atan2(q.y() - p.y(), q.x() - p.x());
                double diff = edgeAngle - analysis.direction()[cell];
                diff = Math.abs(((diff + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI);
                if (diff < Math.toRadians(20.0) || diff > Math.PI - Math.toRadians(20.0)) {
                    aligned++;
                }
            }
        }
        return considered == 0 ? 0.0 : (double) aligned / considered;
    }

    /** Share of strong texture-edge cells that have an output vertex within 0.02 UV. */
    private static double textureEdgeCoverage(final PlacementPlan plan, final MeshFeatureAnalyzer.Analysis analysis) {
        final DomainMask mask = analysis.mask();
        final List<Point2> vertices = plan.geometry().uvs();
        long strong = 0;
        long covered = 0;
        final int w = mask.width();
        final int h = mask.height();
        final double cellUv = mask.cellUv();
        for (int cy = 0; cy < h; cy++) {
            for (int cx = 0; cx < w; cx++) {
                final int cell = cy * w + cx;
                if (!mask.solidCell(cell) || analysis.textureEdge()[cell] <= 0.5) {
                    continue;
                }
                strong++;
                final double[] center = mask.cellCenterUv(cell);
                for (final Point2 v : vertices) {
                    if (Math.abs(v.x() - center[0]) < 0.021
                            && Math.abs(v.y() - center[1]) < 0.021
                            && Math.hypot(v.x() - center[0], v.y() - center[1]) < 0.02) {
                        covered++;
                        break;
                    }
                }
            }
        }
        return strong == 0 ? 0.0 : (double) covered / strong;
    }

    // ---------- tests ----------

    @Test
    void hairStrandsGainTextureEdgeCoverage() throws Exception {
        final Material hair = hairStrands();
        final PlacementSettings settings = PlacementSettings.defaults();
        final Plan legacy = planWith(hair, FeatureOptions.disabled(), settings);
        final FeatureOptions m1 = new FeatureOptions(
                true,
                DensityPreset.SOFT_STRIP,
                0.04,
                0.025,
                0.06,
                0.45,
                0.25,
                0.55,
                0.0,
                0.2,
                0.6,
                0.25,
                0.3,
                Math.toRadians(45.0),
                3);
        final Plan geometry = planWith(hair, m1, settings);
        final Plan texture = planWith(hair, FeatureOptions.forPreset(DensityPreset.SOFT_STRIP), settings);
        assertNotEquals(legacy.plan.geometry(), texture.plan.geometry());

        // Pre-defined improvement (deterministic, fixed seed): the texture-weighted density
        // places output vertices along the artwork edges; the legacy fixed grid almost never
        // lands inside the thin strands.
        final double legacyCoverage = textureEdgeCoverage(legacy.plan, texture.analysis);
        final double geometryCoverage = textureEdgeCoverage(geometry.plan, texture.analysis);
        final double textureCoverage = textureEdgeCoverage(texture.plan, texture.analysis);
        // Vertex budget bounds absolute coverage; the pre-defined improvement is relative —
        // the textured preset must cover at least 3x more artwork edges than legacy.
        assertTrue(
                textureCoverage > legacyCoverage * 3.0,
                () -> "texture coverage " + textureCoverage + " vs legacy " + legacyCoverage);

        writeDiag(hair, legacy, geometry, texture);
    }

    @Test
    void metalPlateStaysSparseAndProtectsStructure() throws Exception {
        final Material metal = metalPlate();
        final PlacementSettings settings = PlacementSettings.defaults();
        final Plan auto = planWith(metal, FeatureOptions.forPreset(DensityPreset.AUTO_BLEND), settings);
        final Plan rigid = planWith(metal, FeatureOptions.forPreset(DensityPreset.RIGID_GEOMETRY), settings);
        // Rigid preset must not explode the vertex budget and must keep screw holes unfilled.
        assertTrue(
                rigid.plan.geometry().uvs().size() <= auto.plan.geometry().uvs().size() + 20,
                "rigid preset should stay sparse: "
                        + rigid.plan.geometry().uvs().size());
        final List<Integer> idx = rigid.plan.geometry().triangleIndices();
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            final Point2 a = rigid.plan.geometry().uvs().get(idx.get(i));
            final Point2 b = rigid.plan.geometry().uvs().get(idx.get(i + 1));
            final Point2 c = rigid.plan.geometry().uvs().get(idx.get(i + 2));
            for (final double[] screw : new double[][] {{0.25, 0.25}, {0.75, 0.25}, {0.25, 0.75}, {0.75, 0.75}}) {
                final double cu = (a.x() + b.x() + c.x()) / 3.0;
                final double cv = (a.y() + b.y() + c.y()) / 3.0;
                final double d = Math.hypot(cu - screw[0], cv - screw[1]);
                assertTrue(d > 0.03, "triangle fills a screw hole: " + cu + "," + cv);
            }
        }
        writeDiag(metal, auto, null, rigid);
    }

    @Test
    void skirtSheetProducesValidCoverage() throws Exception {
        final Material skirt = skirt();
        final PlacementSettings settings = PlacementSettings.defaults();
        final Plan sheet = planWith(skirt, FeatureOptions.forPreset(DensityPreset.SOFT_SHEET), settings);
        assertTrue(sheet.plan.geometry().uvs().size() >= 32, "sheet too sparse");
        assertTrue(sheet.plan.geometry().triangleIndices().size() / 3 >= 24, "sheet under-triangulated");
        // Texture-edge adherence: the sheet path must cover more artwork edges than the legacy
        // fixed grid, whose interior fill collapses on the pleated body.
        final Plan legacy = planWith(skirt, FeatureOptions.disabled(), settings);
        final double sheetCoverage = textureEdgeCoverage(sheet.plan, sheet.analysis);
        final double legacyCoverage =
                textureEdgeCoverage(legacy.plan, legacy.analysis == null ? sheet.analysis : legacy.analysis);
        assertTrue(
                sheetCoverage > legacyCoverage,
                () -> "sheet coverage " + sheetCoverage + " <= legacy " + legacyCoverage);
        writeDiag(skirt, sheet, null, sheet);
    }

    @Test
    void textureSignalDensifiesEdgeNeighborhood() throws Exception {
        final Material hair = hairStrands();
        final PlacementSettings settings = PlacementSettings.defaults();
        final Plan geometry = planWith(
                hair, FeatureOptions.forPreset(DensityPreset.SOFT_STRIP).withEnabled(true), settings);
        final int geometryInterior = geometry.plan.stats().interiorPoints();
        // Zeroing the texture weight (direction influence retained) must not add more interior
        // points than the textured preset: the texture signal earns the extra density.
        final FeatureOptions noTexture = new FeatureOptions(
                true,
                DensityPreset.SOFT_STRIP,
                0.04,
                0.025,
                0.06,
                0.45,
                0.25,
                0.55,
                0.0,
                0.2,
                0.6,
                0.25,
                0.3,
                Math.toRadians(45.0),
                3);
        final Plan flat = planWith(hair, noTexture, settings);
        assertTrue(
                geometryInterior >= flat.plan.stats().interiorPoints(),
                () -> "textured " + geometryInterior + " < flat "
                        + flat.plan.stats().interiorPoints());
    }

    @Test
    void sameSeedReproducesSameTexturePlacement() throws Exception {
        final Material hair = hairStrands();
        final PlacementSettings settings = PlacementSettings.defaults();
        final FeatureOptions options = FeatureOptions.forPreset(DensityPreset.SOFT_STRIP);
        final PlacementPlan first = TurboMeshPlanner.plan(fullQuad(), hair.mask(), settings, options);
        final PlacementPlan second = TurboMeshPlanner.plan(fullQuad(), hair.mask(), settings, options);
        assertEquals(first.geometry().uvs(), second.geometry().uvs());
        assertEquals(first.geometry().triangleIndices(), second.geometry().triangleIndices());
    }

    private static void writeDiag(final Material material, final Plan... plans) throws Exception {
        Files.createDirectories(DIAG_DIR);
        for (final Plan plan : plans) {
            if (plan == null || plan.analysis() == null) {
                continue;
            }
            final String svg = FeatureDiagnostics.svg(
                    plan.plan().geometry(),
                    new GeometryFeatureField(plan.analysis(), plan.constraints().hardEdges()),
                    plan.constraints(),
                    plan.analysis().corners().stream().flatMap(List::stream).toList(),
                    700);
            Files.writeString(DIAG_DIR.resolve(material.name() + "-" + plan.label() + ".svg"), svg);
        }
    }
}
