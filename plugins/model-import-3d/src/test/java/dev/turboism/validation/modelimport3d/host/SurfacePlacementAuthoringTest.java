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
