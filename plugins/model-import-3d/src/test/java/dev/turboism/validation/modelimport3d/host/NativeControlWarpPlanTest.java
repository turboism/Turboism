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
