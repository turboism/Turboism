package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpMeshRigPlanTest {
    static ArtMeshAuthoringSnapshot fromWarp(WarpAuthoringSnapshot b) {
        int count = b.forms().get(0).controlPoints().size();
        var source = Collections.nCopies(count, new Point2(123, 456));
        var uv = Collections.nCopies(count, new Point2(.25f, .75f));
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId(b.deformerId().value()),
                b.sourceGuid(),
                new ArtMeshGeometry(source, uv, count >= 3 ? List.of(0, 1, 2) : List.of()),
                b.axes().stream()
                        .map(a -> new ArtMeshAuthoringSnapshot.Axis(
                                a.parameterGuid(),
                                a.parameterId(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()))
                        .toList(),
                b.cells().stream()
                        .map(c -> new ArtMeshAuthoringSnapshot.Cell(
                                c.formGuid(),
                                c.coordinates().stream()
                                        .map(v -> new ArtMeshAuthoringSnapshot.Coordinate(v.parameterGuid(), v.value()))
                                        .toList()))
                        .toList(),
                b.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                f.formGuid(),
                                f.controlPoints(),
                                123 + Math.floorMod(f.formGuid().hashCode(), 500)))
                        .toList(),
                List.of());
    }

    @Test
    void landmarksUseStoredDefaultAndPreserveSourceUvTopologyOrdersAndUnusedForms() {
        var b = fromWarp(McpWarpRigPlanTest.snapshot(true));
        var edits = McpMeshRigPlan.edits(b, McpWarpRigPlanTest.axes(true));
        assertEquals(8, edits.size());
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(b, edits);
        assertEquals(b.sourceGeometry(), plan.after().sourceGeometry());
        assertEquals(b.axes(), plan.after().axes());
        assertEquals(b.cells(), plan.after().cells());
        assertEquals(b.morphKeys(), plan.after().morphKeys());
        for (int i = 0; i < b.forms().size(); i++)
            assertEquals(
                    b.forms().get(i).drawOrder(), plan.after().forms().get(i).drawOrder());
        assertEquals(
                b.forms().stream()
                        .filter(f ->
                                f.formGuid().equals("f1.0:0.0") || f.formGuid().equals("unreferenced"))
                        .toList(),
                plan.after().forms().stream()
                        .filter(f ->
                                f.formGuid().equals("f1.0:0.0") || f.formGuid().equals("unreferenced"))
                        .toList());
        var target = plan.after().forms().stream()
                .filter(f -> f.formGuid().equals("f3.0:2.0"))
                .findFirst()
                .orElseThrow();
        assertEquals(new Point2(2, 3), target.positions().get(1));
        assertTrue(plan.drawOrders().isEmpty());
        assertTrue(edits.stream().allMatch(e -> e.containsKey("positions") && !e.containsKey("controlPoints")));
    }

    @Test
    void xyCornersAndReplanningAreStable() {
        var b = fromWarp(McpWarpRigPlanTest.snapshot(true));
        var corners = List.of(new McpLandmarkRigPlan.Corner(
                Map.of("x-guid", true, "y-guid", true), List.of(new McpLandmarkRigPlan.Anchor(1, 4, 8))));
        var edits = McpMeshRigPlan.edits(b, McpWarpRigPlanTest.axes(true), corners);
        var after = ((McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(b, edits)).after();
        assertEquals(
                new Point2(6, 11),
                after.forms().stream()
                        .filter(f -> f.formGuid().equals("f3.0:2.0"))
                        .findFirst()
                        .orElseThrow()
                        .positions()
                        .get(1));
        assertEquals(edits, McpMeshRigPlan.edits(after, McpWarpRigPlanTest.axes(true), corners));
        var reversed = new ArrayList<>(McpWarpRigPlanTest.axes(true));
        Collections.reverse(reversed);
        assertEquals(edits, McpMeshRigPlan.edits(b, reversed, corners));
    }

    @Test
    void morphReferencesOnDefaultOrTargetsFailClosedButUnrelatedMorphIsPreserved() {
        var b = fromWarp(McpWarpRigPlanTest.snapshot(true));
        for (String guid : List.of("f1.0:0.0", "f3.0:2.0")) {
            var m = withMorph(b, guid);
            assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(m, McpWarpRigPlanTest.axes(true)));
        }
        var m = withMorph(b, "unreferenced");
        var edits = McpMeshRigPlan.edits(m, McpWarpRigPlanTest.axes(true));
        assertEquals(
                m.morphKeys(),
                ((McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(m, edits))
                        .after()
                        .morphKeys());
    }

    static ArtMeshAuthoringSnapshot withMorph(ArtMeshAuthoringSnapshot b, String guid) {
        return new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", guid, Optional.of(1f))));
    }

    @Test
    void sharedSparseInvalidAnchorsAndPointBudgetAreRejected() {
        var b = fromWarp(McpWarpRigPlanTest.snapshot(true));
        var cells = new ArrayList<>(b.cells());
        cells.remove(0);
        var sparse = new ArtMeshAuthoringSnapshot(
                b.artMeshId(), b.sourceGuid(), b.sourceGeometry(), b.axes(), cells, b.forms(), b.morphKeys());
        assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(sparse, McpWarpRigPlanTest.axes(true)));
        cells = new ArrayList<>(b.cells());
        cells.set(
                0,
                new ArtMeshAuthoringSnapshot.Cell(
                        cells.get(1).formGuid(), cells.get(0).coordinates()));
        var shared = new ArtMeshAuthoringSnapshot(
                b.artMeshId(), b.sourceGuid(), b.sourceGeometry(), b.axes(), cells, b.forms(), b.morphKeys());
        assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(shared, McpWarpRigPlanTest.axes(true)));
        var p = Collections.nCopies(1024, new Point2(0, 0));
        var large = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                new ArtMeshGeometry(p, p, List.of()),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(f.formGuid(), p, 0))
                        .toList(),
                List.of());
        assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(large, McpWarpRigPlanTest.axes(true)));
        var invalid = new McpLandmarkRigPlan.Axis(
                "x-guid", List.of(new McpLandmarkRigPlan.Anchor(99, 1, 1)), McpWarpRigPlanTest.POSITIVE);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpMeshRigPlan.edits(
                        b, List.of(invalid, McpWarpRigPlanTest.axes(true).get(1))));
    }
}
