package dev.turboism.validation.modelimport3d.host;

import static dev.turboism.validation.modelimport3d.host.SurfaceWarpAuthoringTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfacePlacementAuthoringTest {
    @Test
    void wrapsCompleteBoundRollBankWithoutChangingAnglesOriginsOrGrid() {
        var roll = new SurfaceRotationAuthoringTest.State();
        SurfaceRotationAuthoring.create(roll.model(), null, SurfaceRotationAuthoringTest.request());
        var before = roll.snapshot();
        var host = new SurfaceWarpAuthoringTest.State(null, "normal");
        var parent = new java.util.concurrent.atomic.AtomicReference<Optional<DeformerId>>(Optional.empty());
        var child = proxy(RotationDeformer.class, (m, a) -> switch (m.getName()) {
            case "authoringSnapshot" -> roll.snapshot();
            case "parentDeformerId" -> parent.get();
            case "setTargetDeformer" -> {
                parent.set((Optional<DeformerId>) a[0]);
                yield null;
            }
            default -> throw new AssertionError(m);
        });
        var placed = SurfacePlacementAuthoring.wrap(host.model(), null, "Apple", child);
        assertEquals(Optional.of(placed.id()), parent.get());
        assertEquals(before, roll.snapshot());
        assertTrue(placed.authoringSnapshot().axes().isEmpty());
        assertEquals(1, placed.authoringSnapshot().forms().size());
        // Translation through the unbound outer form is added after all X/Y/Z projection.
        // It never changes the per-chart local coordinates or the circle's binding points.
        var shifted = new RotationDeformerForm(0, 23, -11, 1, false, false);
        placed.replaceForm(shifted);
        assertEquals(shifted, placed.authoringSnapshot().forms().get(0).value());
        assertEquals(before, roll.snapshot());
    }

    @Test
    void canvasMappingCentersNormalizedWorkspaceAndFitsShorterEdge() {
        assertEquals(
                new RotationDeformerForm(0, 0, 0, 1, false, false), SurfacePlacementAuthoring.canvasForm(1024, 1024));
        for (double[] size : new double[][] {{2400, 1600}, {600, 1200}, {512, 512}, {4096, 2048}}) {
            var form = SurfacePlacementAuthoring.canvasForm(size[0], size[1]);
            assertEquals(size[0] / 2, form.originX() + 512 * form.scale(), .001);
            assertEquals(size[1] / 2, form.originY() + 512 * form.scale(), .001);
            assertTrue(800 * form.scale() <= Math.min(size[0], size[1]));
        }
        assertThrows(IllegalArgumentException.class, () -> SurfacePlacementAuthoring.canvasForm(0, 1024));
        assertThrows(IllegalArgumentException.class, () -> SurfacePlacementAuthoring.canvasForm(Double.NaN, 1024));
    }

    @Test
    void fitsOnlyTheNewOuterPlacementWithoutMovingExistingModels() {
        var state = new SurfaceWarpAuthoringTest.State(null, "normal");
        var placed = state.placement();
        placed.replaceForm(new RotationDeformerForm(0, 0, 0, 1, false, false));
        var oldId = new DeformerId("previous-import");
        var old = proxy(RotationDeformer.class, (m, a) -> switch (m.getName()) {
            case "id" -> oldId;
            default -> throw new AssertionError("Existing model must not be touched: " + m);
        });
        var host = proxy(CubismModel.class, (m, a) -> switch (m.getName()) {
            case "canvas" -> proxy(Canvas.class, (x, y) -> x.getName().equals("widthPixels") ? 2400f : 1600f);
            case "deformers" ->
                proxy(Deformers.class, (x, y) -> {
                    assertEquals("all", x.getName());
                    return List.of();
                });
            case "rotationDeformers" ->
                proxy(RotationDeformers.class, (x, y) -> {
                    assertEquals("all", x.getName());
                    return List.of(old, placed);
                });
            default -> throw new AssertionError(m);
        });
        SurfacePlacementAuthoring.fitCanvas(host, Set.of(oldId), List.of(), "new-model");
        assertEquals(
                SurfacePlacementAuthoring.canvasForm(2400, 1600),
                placed.authoringSnapshot().forms().get(0).value());
        assertTrue(placed.authoringSnapshot().axes().isEmpty());
    }

    @Test
    void refusesExistingParentBeforeCreatingAnOuterTransform() {
        var child = proxy(WarpDeformer.class, (m, a) -> {
            assertEquals("parentDeformerId", m.getName());
            return Optional.of(new DeformerId("existing"));
        });
        var untouched = proxy(CubismModel.class, (m, a) -> {
            throw new AssertionError("must not create or write");
        });
        assertThrows(
                IllegalStateException.class, () -> SurfacePlacementAuthoring.wrap(untouched, null, "Apple", child));
    }
}
