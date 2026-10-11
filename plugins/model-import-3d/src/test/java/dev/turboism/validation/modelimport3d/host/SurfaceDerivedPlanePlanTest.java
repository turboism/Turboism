package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurfaceDerivedPlanePlanTest {
    @Test
    void shaderUvMatchesOfficialCachedAffineWhenRawImageIsRestored() {
        var original = mesh();
        var xyz = new ArrayList<>(original.sourcePositions());
        var local = new ArrayList<>(original.warpLocal());
        var uv = new ArrayList<>(original.sourceUvs());
        var slots = new ArrayList<>(original.slotIdentities());
        xyz.add(new SurfaceProjection.Vertex(.05859375, .4, 0));
        local.add(new Point2(.25f + .25f * 30f / 512f, .6f));
        uv.add(new Point2(.2f, .3f));
        slots.add(new SurfaceWarpChartPlan.Slot(0, 6, 0));
        var plan = SurfaceDerivedPlanePlan.compile(
                new SurfaceWarpChartPlan.Mesh(xyz, uv, local, slots, List.of(0, 6, 2, 4, 5, 3), original.triangles()));
        int point = plan.oldToNew().get(6);
        assertEquals(34f, plan.geometry().positions().get(point).x());
        // Official 5.3.02 CAffine replay gives these exact bits. Float division
        // gives 0x3d71c71c and changes after raw-image display / atlas Undo.
        assertEquals(
                0x3d71c71d,
                Float.floatToRawIntBits(plan.geometry().uvs().get(point).x()));
    }

    private static SurfaceWarpChartPlan.Mesh mesh() {
        var xyz = List.of(
                new SurfaceProjection.Vertex(0, 0, 0),
                new SurfaceProjection.Vertex(1, 0, 0),
                new SurfaceProjection.Vertex(1, 1, 0),
                new SurfaceProjection.Vertex(0, 1, 0),
                new SurfaceProjection.Vertex(0, 0, 0),
                new SurfaceProjection.Vertex(1, 1, 0));
        var local = List.of(
                new Point2(.25f, .5f),
                new Point2(.5f, .5f),
                new Point2(.5f, .75f),
                new Point2(.25f, .75f),
                new Point2(.25f, .5f),
                new Point2(.5f, .75f));
        // Deliberately distinct material UVs at a geometrically identical corner.
        var uv = List.of(
                new Point2(0, 0),
                new Point2(1, 0),
                new Point2(1, 1),
                new Point2(0, 1),
                new Point2(.75f, .1f),
                new Point2(1, 1));
        var slots = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new SurfaceWarpChartPlan.Slot(0, i, 0))
                .toList();
        return new SurfaceWarpChartPlan.Mesh(
                xyz,
                uv,
                local,
                slots,
                List.of(0, 1, 2, 4, 5, 3),
                List.of(new SurfaceWarpChartPlan.Triangle(0, 0), new SurfaceWarpChartPlan.Triangle(0, 1)));
    }

    @Test
    void preservesAllOriginalCornersAndUvProvenanceWhileWeldingOnlyDerivedSlots() {
        var original = mesh();
        var plan = SurfaceDerivedPlanePlan.compile(original);
        assertSame(original, plan.original());
        assertEquals(List.of(0, 1, 2, 3, 0, 2), plan.oldToNew());
        assertEquals(List.of(0, 1, 2, 0, 2, 3), plan.geometry().triangleIndices());
        assertEquals(
                List.of(new Point2(4, 4), new Point2(516, 4), new Point2(516, 516), new Point2(4, 516)),
                plan.geometry().positions());
        assertNotEquals(original.sourceUvs().get(0), original.sourceUvs().get(4));
        for (int corner = 0; corner < original.indices().size(); corner++) {
            int old = original.indices().get(corner),
                    mapped = plan.geometry().triangleIndices().get(corner);
            assertEquals(original.warpLocal().get(old), plan.defaultLocal().get(mapped));
            assertEquals(
                    original.sourcePositions().get(old),
                    original.sourcePositions().get(plan.representatives().get(mapped)));
        }
        assertEquals(new Point2(4f / 576, 4f / 576), plan.geometry().uvs().get(0));
        assertThrows(UnsupportedOperationException.class, () -> plan.oldToNew().clear());
    }

    @Test
    void distinctWorldPositionsAtSamePlanePointFailWithoutDroppingTheirFaces() {
        var original = mesh();
        var xyz = new ArrayList<>(original.sourcePositions());
        xyz.set(4, new SurfaceProjection.Vertex(0, 0, 1));
        var bad = new SurfaceWarpChartPlan.Mesh(
                xyz,
                original.sourceUvs(),
                original.warpLocal(),
                original.slotIdentities(),
                original.indices(),
                original.triangles());
        assertThrows(IllegalArgumentException.class, () -> SurfaceDerivedPlanePlan.compile(bad));
        assertEquals(6, bad.indices().size());
    }

    @Test
    void quantizationCannotCollapseDistinctWarpLocalCoordinates() {
        var original = mesh();
        var local = new ArrayList<>(original.warpLocal());
        local.set(4, new Point2(Math.nextUp(.25f), .5f));
        var bad = new SurfaceWarpChartPlan.Mesh(
                original.sourcePositions(),
                original.sourceUvs(),
                local,
                original.slotIdentities(),
                original.indices(),
                original.triangles());
        assertThrows(IllegalArgumentException.class, () -> SurfaceDerivedPlanePlan.compile(bad));
    }

    @Test
    void rejectsMixedChartsAndInvalidCornerDomain() {
        var original = mesh();
        var slots = new ArrayList<>(original.slotIdentities());
        slots.set(4, new SurfaceWarpChartPlan.Slot(0, 4, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceDerivedPlanePlan.compile(new SurfaceWarpChartPlan.Mesh(
                        original.sourcePositions(),
                        original.sourceUvs(),
                        original.warpLocal(),
                        slots,
                        original.indices(),
                        original.triangles())));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceDerivedPlanePlan.compile(new SurfaceWarpChartPlan.Mesh(
                        original.sourcePositions(),
                        original.sourceUvs(),
                        original.warpLocal(),
                        original.slotIdentities(),
                        List.of(0, 1, 999, 4, 5, 3),
                        original.triangles())));
    }

    @Test
    void retainsDegenerateDuplicateAndReversedFacesForNativeAdmission() {
        var original = mesh();
        var faces = List.of(0, 1, 2, 0, 1, 2, 2, 1, 0, 0, 0, 0);
        var triangles = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new SurfaceWarpChartPlan.Triangle(0, i))
                .toList();
        var plan = SurfaceDerivedPlanePlan.compile(new SurfaceWarpChartPlan.Mesh(
                original.sourcePositions(),
                original.sourceUvs(),
                original.warpLocal(),
                original.slotIdentities(),
                faces,
                triangles));
        assertEquals(faces, plan.geometry().triangleIndices());
    }
}
