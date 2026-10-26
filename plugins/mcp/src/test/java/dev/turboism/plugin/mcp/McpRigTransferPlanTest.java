package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpRigTransferPlanTest {
    static final McpRigTransferPlan.Transform MIRROR = new McpRigTransferPlan.Transform(-1, 0, 0, 1);

    static List<McpRigTransferPlan.Axis> axes() {
        return List.of(new McpRigTransferPlan.Axis(
                "axis",
                "mapped",
                List.of(
                        new McpRigTransferPlan.Key(-1, 10),
                        new McpRigTransferPlan.Key(0, 20),
                        new McpRigTransferPlan.Key(1, 30))));
    }

    static List<Integer> points() {
        return List.of(3, 2, 1, 0);
    }

    static WarpAuthoringSnapshot warp(boolean target) {
        var b = McpKeyformContinuityTest.warp();
        var forms = b.forms().stream()
                .map(f -> new WarpAuthoringSnapshot.Form(
                        target ? "t" + f.formGuid() : f.formGuid(),
                        target
                                ? Collections.nCopies(
                                        4, f.formGuid().equals("f1") ? new Point2(100, 200) : new Point2(-999, -999))
                                : f.controlPoints()))
                .toList();
        return new WarpAuthoringSnapshot(
                new DeformerId(target ? "target" : "source"),
                target ? "target-guid" : "source-guid",
                1,
                1,
                target,
                List.of(new WarpAuthoringSnapshot.Axis(
                        target ? "mapped" : "axis",
                        new ParameterId("same-display-id"),
                        target ? 10 : -1,
                        target ? 20 : 0,
                        target ? 30 : 1,
                        target ? List.of(30f, 10f, 20f) : List.of(-1f, 0f, 1f))),
                b.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell(
                                target ? "t" + c.formGuid() : c.formGuid(),
                                c.coordinates().stream()
                                        .map(x -> new WarpAuthoringSnapshot.Coordinate(
                                                target ? "mapped" : "axis", target ? 20 + 10 * x.value() : x.value()))
                                        .toList()))
                        .toList(),
                forms);
    }

    static RotationAuthoringSnapshot rotation(boolean target) {
        var w = warp(target);
        var b = McpKeyformContinuityTest.rotation();
        return new RotationAuthoringSnapshot(
                w.deformerId(),
                w.sourceGuid(),
                target ? 500 : 77,
                w.axes().stream()
                        .map(a -> new RotationAuthoringSnapshot.Axis(
                                a.parameterGuid(),
                                a.parameterId(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()))
                        .toList(),
                w.cells().stream()
                        .map(c -> new RotationAuthoringSnapshot.Cell(
                                c.formGuid(),
                                c.coordinates().stream()
                                        .map(x ->
                                                new RotationAuthoringSnapshot.Coordinate(x.parameterGuid(), x.value()))
                                        .toList()))
                        .toList(),
                b.forms().stream()
                        .map(f -> new RotationAuthoringSnapshot.Form(
                                target ? "t" + f.formGuid() : f.formGuid(),
                                target
                                        ? f.formGuid().equals("f1")
                                                ? new RotationDeformerForm(100, 20, 30, 3, true, false)
                                                : new RotationDeformerForm(999, -999, -999, 7, false, true)
                                        : f.value()))
                        .toList());
    }

    static Object snapshot(String kind, boolean target) {
        return kind.equals("rotation_deformer")
                ? rotation(target)
                : kind.equals("art_mesh") ? McpMeshRigPlanTest.fromWarp(warp(target)) : warp(target);
    }

    static McpRigTransferPlan.Result plan(Object source, Object target) {
        boolean rotation = source instanceof RotationAuthoringSnapshot;
        return McpRigTransferPlan.plan(
                source, target, axes(), MIRROR, rotation ? List.of() : points(), rotation ? -1d : null);
    }

    @Test
    void warpCopiesRelativeDisplacementsAndPreservesTargetDefaultGridAndSource() {
        var source = warp(false);
        var target = warp(true);
        var result = plan(source, target);
        var after = ((McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(target, result.edits())).after();
        assertEquals(2, result.edits().size());
        assertEquals(8, result.plannedPoints());
        assertEquals("f1", result.sourceDefaultFormGuid());
        assertEquals("tf1", result.targetDefaultFormGuid());
        assertEquals(target.axes(), after.axes());
        assertEquals(target.cells(), after.cells());
        assertEquals(target.quadTransform(), after.quadTransform());
        assertEquals(target.forms().get(1), after.forms().get(1));
        assertEquals(
                Collections.nCopies(4, new Point2(99, 200)),
                after.forms().get(2).controlPoints());
        assertEquals(
                Collections.nCopies(4, new Point2(101, 200)),
                after.forms().get(0).controlPoints());
        assertEquals(source, warp(false));
        assertEquals(target, warp(true));
    }

    @Test
    void meshUsesTargetIndexToSourceIndexPermutationWithoutCopyingSourceUvOrOrder() {
        var sw = warp(false);
        var changed = new WarpAuthoringSnapshot(
                sw.deformerId(),
                sw.sourceGuid(),
                1,
                1,
                false,
                sw.axes(),
                sw.cells(),
                List.of(
                        sw.forms().get(0),
                        sw.forms().get(1),
                        new WarpAuthoringSnapshot.Form(
                                "f2",
                                List.of(new Point2(1, 0), new Point2(3, 0), new Point2(3, 1), new Point2(5, 1)))));
        var source = McpMeshRigPlanTest.fromWarp(changed);
        var target = McpMeshRigPlanTest.fromWarp(warp(true));
        var result = plan(source, target);
        var after = ((McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(target, result.edits())).after();
        assertEquals(
                List.of(new Point2(96, 200), new Point2(97, 200), new Point2(98, 200), new Point2(99, 200)),
                after.forms().get(2).positions());
        assertEquals(target.sourceGeometry(), after.sourceGeometry());
        assertEquals(target.forms().get(1), after.forms().get(1));
        for (int i = 0; i < target.forms().size(); i++) {
            assertEquals(target.forms().get(i).drawOrder(), after.forms().get(i).drawOrder());
            assertEquals(target.forms().get(i).opacity(), after.forms().get(i).opacity());
        }
    }

    @Test
    void rotationTransfersRawAngleOriginScaleRatioAndExplicitRelativeReflections() {
        var target = rotation(true);
        var result = plan(rotation(false), target);
        var after = ((McpKeyformWritePlan.Rotation) McpKeyformWritePlan.prepare(target, result.edits())).after();
        assertEquals(
                new RotationDeformerForm(90, 19, 30, 6, false, true),
                after.forms().get(2).value());
        assertEquals(-250, after.forms().get(0).value().angle());
        assertEquals(target.forms().get(1), after.forms().get(1));
        assertEquals(500, after.baseAngle());
        assertEquals(target.axes(), after.axes());
    }

    @Test
    void explicitKeyMapCanReverseSidesWithoutDisplayIdOrNearestValueGuessing() {
        var reversed = List.of(new McpRigTransferPlan.Axis(
                "axis",
                "mapped",
                List.of(
                        new McpRigTransferPlan.Key(-1, 30),
                        new McpRigTransferPlan.Key(0, 20),
                        new McpRigTransferPlan.Key(1, 10))));
        var target = warp(true);
        var result = McpRigTransferPlan.plan(warp(false), target, reversed, MIRROR, points(), null);
        var after = ((McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(target, result.edits())).after();
        assertEquals(new Point2(101, 200), after.forms().get(2).controlPoints().get(0));
    }

    @Test
    void completeThreeAxisGridUsesFullGuidTuplesAndPreservesAllCombinationForms() {
        var source = grid(false);
        var target = grid(true);
        var mapping = new ArrayList<McpRigTransferPlan.Axis>();
        for (int i = 0; i < 3; i++) {
            var a = source.axes().get(i);
            var t = target.axes().get(i);
            mapping.add(new McpRigTransferPlan.Axis(
                    a.parameterGuid(),
                    t.parameterGuid(),
                    a.keys().stream().map(k -> new McpRigTransferPlan.Key(k, k)).toList()));
        }
        Collections.reverse(mapping);
        var result = McpRigTransferPlan.plan(
                source, target, mapping, new McpRigTransferPlan.Transform(1, 0, 0, 1), List.of(0, 1, 2, 3), null);
        assertEquals(26, result.edits().size());
        assertEquals(104, result.plannedPoints());
        assertTrue(result.edits().stream().allMatch(e -> ((List<?>) e.get("coordinates")).size() == 3));
    }

    private static WarpAuthoringSnapshot grid(boolean target) {
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        return new WarpAuthoringSnapshot(
                new DeformerId(target ? "target" : "source"),
                target ? "target-guid" : "source-guid",
                b.rows(),
                b.columns(),
                b.quadTransform(),
                b.axes(),
                b.cells(),
                b.forms().stream().filter(f -> !f.formGuid().equals("unused")).toList());
    }

    @Test
    void fullSixtyFiveCellTransferKeepsInitializerLimitsUnchanged() {
        var keys =
                java.util.stream.IntStream.range(0, 65).mapToObj(i -> (float) i).toList();
        var positions = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        var axis = new WarpAuthoringSnapshot.Axis("axis", new ParameterId("parameter"), 0, 32, 64, keys);
        var cells = keys.stream()
                .map(k -> new WarpAuthoringSnapshot.Cell(
                        "f" + k, List.of(new WarpAuthoringSnapshot.Coordinate("axis", k))))
                .toList();
        var forms = keys.stream()
                .map(k -> new WarpAuthoringSnapshot.Form("f" + k, positions))
                .toList();
        var source =
                new WarpAuthoringSnapshot(new DeformerId("source"), "s-guid", 1, 1, false, List.of(axis), cells, forms);
        var target =
                new WarpAuthoringSnapshot(new DeformerId("target"), "t-guid", 1, 1, false, List.of(axis), cells, forms);
        var mapping = List.of(new McpRigTransferPlan.Axis(
                "axis",
                "axis",
                keys.stream().map(k -> new McpRigTransferPlan.Key(k, k)).toList()));
        var result = McpRigTransferPlan.plan(
                source, target, mapping, new McpRigTransferPlan.Transform(1, 0, 0, 1), List.of(0, 1, 2, 3), null);
        assertEquals(64, result.edits().size());
        assertEquals(256, result.plannedPoints());
        assertThrows(McpBindingGridSnapshot.Budget.class, () -> McpBindingGridSnapshot.view(source));
    }

    @Test
    void sourceSharedOrdinaryFormsAreReadButSharedTargetFormsAreRejected() {
        var s = warp(false);
        var t = warp(true);
        var sharedSource = new WarpAuthoringSnapshot(
                s.deformerId(),
                s.sourceGuid(),
                1,
                1,
                false,
                s.axes(),
                s.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell("f1", c.coordinates()))
                        .toList(),
                List.of(s.forms().get(1)));
        var r = plan(sharedSource, t);
        assertEquals(2, r.edits().size());
        var sharedTarget = new WarpAuthoringSnapshot(
                t.deformerId(),
                t.sourceGuid(),
                1,
                1,
                false,
                t.axes(),
                t.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell("tf1", c.coordinates()))
                        .toList(),
                List.of(t.forms().get(1)));
        assertThrows(IllegalArgumentException.class, () -> plan(s, sharedTarget));
    }

    @Test
    void axisKeysDefaultsAndVertexCorrespondencesMustBeCompleteAndOneToOne() {
        var s = warp(false);
        var t = warp(true);
        for (var mapping : List.of(
                List.<McpRigTransferPlan.Axis>of(),
                List.of(new McpRigTransferPlan.Axis(
                        "wrong", "mapped", axes().get(0).keys())),
                List.of(new McpRigTransferPlan.Axis(
                        "axis",
                        "mapped",
                        List.of(
                                new McpRigTransferPlan.Key(-1, 10),
                                new McpRigTransferPlan.Key(0, 30),
                                new McpRigTransferPlan.Key(1, 20))))))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpRigTransferPlan.plan(s, t, mapping, MIRROR, points(), null));
        for (var p : List.of(List.of(0, 1, 2), List.of(0, 1, 2, 2), List.of(0, 1, 2, 4)))
            assertThrows(IllegalArgumentException.class, () -> McpRigTransferPlan.plan(s, t, axes(), MIRROR, p, null));
        assertThrows(IllegalArgumentException.class, () -> new McpRigTransferPlan.Transform(1, 2, 2, 4));
    }

    @Test
    void rejectsIncompleteGridsOrphansMorphAndNativeFloatOverflow() {
        var s = warp(false);
        var t = warp(true);
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan(
                        new WarpAuthoringSnapshot(
                                s.deformerId(),
                                s.sourceGuid(),
                                1,
                                1,
                                false,
                                s.axes(),
                                s.cells().subList(0, 2),
                                s.forms()),
                        t));
        var forms = new ArrayList<>(s.forms());
        forms.add(new WarpAuthoringSnapshot.Form("orphan", s.forms().get(0).controlPoints()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan(
                        new WarpAuthoringSnapshot(
                                s.deformerId(), s.sourceGuid(), 1, 1, false, s.axes(), s.cells(), forms),
                        t));
        var mesh = McpMeshRigPlanTest.fromWarp(s);
        var morph = new ArtMeshAuthoringSnapshot(
                mesh.artMeshId(),
                mesh.sourceGuid(),
                mesh.sourceGeometry(),
                mesh.axes(),
                mesh.cells(),
                mesh.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "f2", Optional.of(1f))));
        assertThrows(UnsupportedOperationException.class, () -> plan(morph, McpMeshRigPlanTest.fromWarp(t)));
        var extreme = new WarpAuthoringSnapshot(
                s.deformerId(),
                s.sourceGuid(),
                1,
                1,
                false,
                s.axes(),
                s.cells(),
                List.of(
                        s.forms().get(0),
                        s.forms().get(1),
                        new WarpAuthoringSnapshot.Form("f2", Collections.nCopies(4, new Point2(Float.MAX_VALUE, 0)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRigTransferPlan.plan(
                        extreme, t, axes(), new McpRigTransferPlan.Transform(1e6, 0, 0, 1), points(), null));
    }

    @Test
    void plannedPointBudgetAndMixedKindsFailWithoutPartialEdits() {
        var s = warp(false);
        var t = warp(true);
        int n = 2050;
        var bigS = new WarpAuthoringSnapshot(
                s.deformerId(),
                s.sourceGuid(),
                1,
                n / 2 - 1,
                false,
                s.axes(),
                s.cells(),
                s.forms().stream()
                        .map(f ->
                                new WarpAuthoringSnapshot.Form(f.formGuid(), Collections.nCopies(n, new Point2(0, 0))))
                        .toList());
        var bigT = new WarpAuthoringSnapshot(
                t.deformerId(),
                t.sourceGuid(),
                1,
                n / 2 - 1,
                false,
                t.axes(),
                t.cells(),
                t.forms().stream()
                        .map(f ->
                                new WarpAuthoringSnapshot.Form(f.formGuid(), Collections.nCopies(n, new Point2(0, 0))))
                        .toList());
        assertThrows(
                McpRigTransferPlan.Budget.class,
                () -> McpRigTransferPlan.plan(
                        bigS,
                        bigT,
                        axes(),
                        MIRROR,
                        java.util.stream.IntStream.range(0, n).boxed().toList(),
                        null));
        assertThrows(UnsupportedOperationException.class, () -> plan(s, McpMeshRigPlanTest.fromWarp(t)));
    }
}
