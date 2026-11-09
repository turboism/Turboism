package dev.turboism.plugin.turbomesh.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the domain decision of one adaptive run.
 *
 * <p>The region planner works on the whole atlas page, so its result is only usable when it stays
 * inside the uv footprint of the source mesh. These cases pin that contract, not the geometry of
 * either planner.</p>
 */
class PlacementDomainPlannerTest {

    private static final int TEXTURE_SIZE = 128;

    private static final double DISC_CENTER = 64.0;

    private static final double DISC_RADIUS = 48.0;

    private static final double FAR_DISC_CENTER = 192.0;

    /** Uv distance that a region plan may leave the source footprint, matching the planner. */
    private static final float FOOTPRINT_TOLERANCE_UV = 0.15f;

    @Test
    void textureRunPlansOnTheArtworkRegions() throws Exception {
        final List<String> diagnostics = new ArrayList<>();
        final TexturePixels texture = discTexture(TEXTURE_SIZE, DISC_CENTER);

        final TurboMeshPlanner.PlacementPlan plan = PlacementDomainPlanner.plan(
                unitQuad(), texture, PlacementSettings.defaults(), FeatureOptions.defaults(), diagnostics::add);

        assertEquals("texture-regions", plan.stats().presetName(), "the region plan must own the run");
        assertTrue(
                diagnostics.get(0).contains("texture regions"),
                "the run must report the region domain but reported " + diagnostics);
        assertValidGeometry(plan.geometry(), 0.0f, 1.0f);
    }

    @Test
    void regionPlanThatLeavesTheFootprintFallsBackToTheAlphaMask() throws Exception {
        final List<String> diagnostics = new ArrayList<>();
        // The page holds a second disc that the source mesh does not cover.
        final TexturePixels texture = twoDiscTexture();

        final TurboMeshPlanner.PlacementPlan plan = PlacementDomainPlanner.plan(
                unitQuad(), texture, PlacementSettings.defaults(), FeatureOptions.defaults(), diagnostics::add);

        assertTrue(
                diagnostics.stream().anyMatch(line -> line.contains("left the uv footprint")),
                "the run must report the footprint fallback but reported " + diagnostics);
        assertEquals("AUTO_BLEND", plan.stats().presetName(), "the alpha-mask path must own the run");
        assertValidGeometry(plan.geometry(), 0.0f, 1.0f);
    }

    @Test
    void runWithoutPixelsKeepsTheGeometryFallback() throws Exception {
        final List<String> diagnostics = new ArrayList<>();

        final TurboMeshPlanner.PlacementPlan plan = PlacementDomainPlanner.plan(
                unitQuad(), null, PlacementSettings.defaults(), FeatureOptions.defaults(), diagnostics::add);

        assertTrue(
                diagnostics.get(0).contains("no texture pixels"),
                "the run must report the geometry fallback but reported " + diagnostics);
        assertValidGeometry(plan.geometry(), 0.0f, 1.0f);
    }

    /** Every vertex pair must match, every index must stay in range and every uv must stay in bounds. */
    private static void assertValidGeometry(final ArtMeshGeometry geometry, final float lowUv, final float highUv) {
        assertEquals(geometry.positions().size(), geometry.uvs().size());
        assertTrue(geometry.triangleIndices().size() % 3 == 0, "triangle indices must come in triples");
        assertTrue(geometry.triangleIndices().size() >= 3, "the plan must publish triangles");
        for (final int index : geometry.triangleIndices()) {
            assertTrue(index >= 0 && index < geometry.uvs().size(), "index " + index + " leaves the vertex list");
        }
        for (final Point2 uv : geometry.uvs()) {
            assertTrue(
                    uv.x() >= lowUv - FOOTPRINT_TOLERANCE_UV && uv.x() <= highUv + FOOTPRINT_TOLERANCE_UV,
                    "uv " + uv.x() + " leaves the footprint");
            assertTrue(
                    uv.y() >= lowUv - FOOTPRINT_TOLERANCE_UV && uv.y() <= highUv + FOOTPRINT_TOLERANCE_UV,
                    "uv " + uv.y() + " leaves the footprint");
        }
    }

    /** Source mesh of every case: a unit square whose uvs span the whole texture. */
    private static ArtMeshGeometry unitQuad() {
        final List<Point2> corners =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(corners, corners, List.of(0, 1, 2, 0, 2, 3));
    }

    private static TexturePixels discTexture(final int size, final double centerX) {
        return discTexture(size, centerX, centerX);
    }

    private static TexturePixels discTexture(final int size, final double centerX, final double centerY) {
        final int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final boolean solid = Math.hypot(x + 0.5 - centerX, y + 0.5 - centerY) <= DISC_RADIUS;
                argb[y * size + x] = solid ? 0xFF3060A0 : 0x00000000;
            }
        }
        return new TexturePixels(size, size, argb, new double[] {size, 0.0, 0.0, 0.0, size, 0.0});
    }

    /** Page of two discs; a unit quad covers the left one only. */
    private static TexturePixels twoDiscTexture() {
        final int size = 2 * TEXTURE_SIZE;
        final int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final boolean left = Math.hypot(x + 0.5 - DISC_CENTER, y + 0.5 - DISC_CENTER) <= DISC_RADIUS;
                final boolean right = Math.hypot(x + 0.5 - FAR_DISC_CENTER, y + 0.5 - FAR_DISC_CENTER) <= DISC_RADIUS;
                argb[y * size + x] = left || right ? 0xFF3060A0 : 0x00000000;
            }
        }
        // The affine maps the left disc into uv [0, 1] and the right disc far outside that range.
        return new TexturePixels(size, size, argb, new double[] {TEXTURE_SIZE, 0.0, 0.0, 0.0, TEXTURE_SIZE, 0.0});
    }
}
