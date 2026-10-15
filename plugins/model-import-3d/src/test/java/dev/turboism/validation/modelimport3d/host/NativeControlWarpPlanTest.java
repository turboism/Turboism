package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeControlWarpPlanTest {
    private static ViewerScene.Mesh mesh(
            float[] positions, List<ViewerScene.ControlSample> samples, List<ViewerScene.OpacityControl> factors) {
        return new ViewerScene.Mesh(
                "Source",
                positions,
                null,
                new float[6],
                null,
                null,
                new int[] {0, 1, 2},
                0,
                true,
                "Source",
                "Head",
                samples,
                factors);
    }

    private static ViewerScene scene(List<ViewerScene.Mesh> meshes, List<ViewerScene.Control> controls) {
        var material = new ViewerScene.Material(
                "Flat", new float[] {1, 1, 1, 1}, -1, -1, new float[3], "OPAQUE", .5f, true, true);
        return new ViewerScene(
                meshes,
                List.of(material),
                List.of(),
                new ViewerScene.Bounds(-1, -1, -1, 1, 1, 1),
                new ViewerScene.Stats(0, meshes.size(), meshes.size(), 1, 0, 0, 0),
                controls);
    }

    private static NativeCharacterPlan.Plan geometry(ViewerScene scene) {
        var request = new SurfaceImport.Request(
                "Test",
                new ParameterId("Y"),
                new ParameterId("X"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                Optional.of(new ParameterId("Z")));
        return NativeCharacterPlan.prepare(scene, request);
    }

    @Test
    void expandedCoordinateBudgetAcceptsLargeControlsAndRejectsOverflow() {
        // Previously rejected at 1M controls or 6M combined slots.
        assertDoesNotThrow(() -> NativeControlWarpPlan.checkBudget(2_000_000, 4_826_981));
        long limit = NativeControlWarpPlan.MAX_AUTHORED_COORDINATE_BYTES / (2L * Float.BYTES);
        assertDoesNotThrow(() -> NativeControlWarpPlan.checkBudget(limit - 6_000_000, 6_000_000));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> NativeControlWarpPlan.checkBudget(limit - 6_000_000 + 1, 6_000_000));
        assertTrue(failure.getMessage().contains("maximumBytes=268435456"));
        assertTrue(failure.getMessage().contains("geometrySlots=6000000"));
        assertThrows(IllegalArgumentException.class, () -> NativeControlWarpPlan.checkBudget(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> NativeControlWarpPlan.checkBudget(1, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> NativeControlWarpPlan.checkBudget(-1, 1));
    }

    @Test
    void localGridFitsTranslationAndCachePreservesExactResult() {
        var moving = mesh(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                List.of(new ViewerScene.ControlSample("Gaze", 1, new float[] {.01f, 0, 0, .01f, 0, 0, .01f, 0, 0})),
                List.of());
        var remote = mesh(new float[] {20, 20, 0, 21, 20, 0, 20, 21, 0}, List.of(), List.of());
        var scene = scene(
                List.of(moving, remote),
                List.of(
                        new ViewerScene.Control("Gaze", "Gaze", -1, 1),
                        new ViewerScene.Control("Unused", "Unused", 0, 1)));
        var geometry = geometry(scene);
        var cache = new NativeControlWarpPlan.FitCache();
        var plan = NativeControlWarpPlan.prepare(scene, geometry, cache);
        assertEquals(plan, NativeControlWarpPlan.prepare(scene, geometry, cache));
        assertEquals(
                List.of("Gaze"),
                plan.controls().stream().map(ViewerScene.Control::id).toList());
        assertEquals(1, plan.patches().size());
        assertTrue(plan.patches().get(0).span().x() < .2f, "Local grid must cover its own patch");
        assertTrue(plan.maximumResidualPixels() < .001);
        assertTrue(plan.controlSamples() > 0);
        assertEquals(2, geometry.totalTriangles());
    }

    @Test
    void opacityOnlyControlsDoNotCreateEmptyGeometryPatches() {
        var mesh = mesh(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                List.of(),
                List.of(new ViewerScene.OpacityControl("Outfit", 1, 0)));
        var scene = scene(List.of(mesh), List.of(new ViewerScene.Control("Outfit", "Outfit", 0, 1)));
        var plan = NativeControlWarpPlan.prepare(scene, geometry(scene));
        assertEquals(1, plan.controls().size());
        assertTrue(plan.patches().isEmpty());
        assertEquals(0, plan.controlSamples());
    }

    @Test
    void depthOnlyShapeQuantifiesNeutralFitSideViewLimit() {
        var mesh = mesh(
                new float[] {-1, -1, 0, 1, -1, 0, 0, 1, 0},
                List.of(new ViewerScene.ControlSample("Depth", 1, new float[] {0, 0, .2f, 0, 0, .2f, 0, 0, .2f})),
                List.of());
        var scene = scene(List.of(mesh), List.of(new ViewerScene.Control("Depth", "Depth", 0, 1)));
        var geometry = geometry(scene);
        var fit = NativeControlWarpPlan.prepare(scene, geometry);
        assertEquals(0, fit.maximumResidualPixels());
        var patch = fit.patches().get(0);
        assertEquals(
                NativeControlWarpPlan.identity(patch.cells()).controlPoints(),
                patch.keys().get(0).controls());
        var partition = geometry.partitions().get(0);
        var displaced = partition.positions().stream()
                .map(v -> new SurfaceProjection.Vertex(v.x(), v.y(), v.z() + .2f))
                .toList();
        for (var pose : List.of(
                new SurfaceProjection.Pose(-30, 0),
                new SurfaceProjection.Pose(30, 0),
                new SurfaceProjection.Pose(0, -30),
                new SurfaceProjection.Pose(0, 30))) {
            var baseline = SurfaceProjection.project(partition.positions(), geometry.camera(), pose)
                    .positions();
            var target = SurfaceProjection.project(displaced, geometry.camera(), pose)
                    .positions();
            // This is an explicit limitation measurement, not side-view acceptance.
            // A neutral identity Warp cannot reproduce this projected depth displacement.
            double error = Math.hypot(
                    target.get(0).x() - baseline.get(0).x(),
                    target.get(0).y() - baseline.get(0).y());
            assertEquals(28.28427, error, .001);
            assertTrue(error > NativeControlWarpPlan.MAX_RESIDUAL_PIXELS);
        }
    }

    @Test
    void onePassReportsEveryUnfittablePartitionForBatchRefinement() {
        var first = mesh(
                new float[] {0, 0, -1, 0, 0, 1, 1, 1, 0},
                List.of(new ViewerScene.ControlSample("Shape", 1, new float[] {1, 0, 0, -1, 0, 0, 0, 0, 0})),
                List.of());
        var second = mesh(new float[] {2, 0, -1, 2, 0, 1, 3, 1, 0}, first.controlSamples(), List.of());
        var scene = scene(List.of(first, second), List.of(new ViewerScene.Control("Shape", "Shape", 0, 1)));
        var failure = assertThrows(
                NativeControlWarpPlan.FitFailure.class, () -> NativeControlWarpPlan.prepare(scene, geometry(scene)));
        assertEquals(0, failure.sourceMesh);
        assertEquals(1, failure.getSuppressed().length);
        var other = assertInstanceOf(NativeControlWarpPlan.FitFailure.class, failure.getSuppressed()[0]);
        assertEquals(1, other.sourceMesh);
        assertEquals(List.of(0), other.triangles);
    }

    @Test
    void coincidentProjectedInputsWithDifferentDisplacementsFailExplicitly() {
        var mesh = mesh(
                new float[] {0, 0, -1, 0, 0, 1, 1, 1, 0},
                List.of(new ViewerScene.ControlSample("Shape", 1, new float[] {1, 0, 0, -1, 0, 0, 0, 0, 0})),
                List.of());
        var scene = scene(List.of(mesh), List.of(new ViewerScene.Control("Shape", "Shape", 0, 1)));
        var geometry = geometry(scene);
        var failure = assertThrows(
                NativeControlWarpPlan.FitFailure.class, () -> NativeControlWarpPlan.prepare(scene, geometry));
        assertEquals(List.of(0), failure.triangles);
        assertEquals(1, geometry.totalTriangles());
    }
}
