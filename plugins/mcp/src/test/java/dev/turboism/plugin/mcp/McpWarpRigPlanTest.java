package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpWarpRigPlanTest {
    static final List<Point2> POINTS = List.of(
            new Point2(0, 0), new Point2(1, 0), new Point2(2, 0), new Point2(0, 1), new Point2(1, 1), new Point2(2, 1));
    static final List<McpLandmarkRigPlan.Anchor> NEGATIVE =
            List.of(new McpLandmarkRigPlan.Anchor(0, -2, 0), new McpLandmarkRigPlan.Anchor(2, 0, 0));
    static final List<McpLandmarkRigPlan.Anchor> POSITIVE =
            List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0), new McpLandmarkRigPlan.Anchor(2, 2, 0));

    static List<McpLandmarkRigPlan.Axis> axes(boolean xy) {
        var result = new ArrayList<McpLandmarkRigPlan.Axis>();
        result.add(new McpLandmarkRigPlan.Axis("x-guid", NEGATIVE, POSITIVE));
        if (xy)
            result.add(new McpLandmarkRigPlan.Axis(
                    "y-guid",
                    List.of(new McpLandmarkRigPlan.Anchor(0, 0, -3)),
                    List.of(new McpLandmarkRigPlan.Anchor(0, 0, 3))));
        return List.copyOf(result);
    }

    static WarpAuthoringSnapshot snapshot(boolean xy) {
        var axes = new ArrayList<WarpAuthoringSnapshot.Axis>();
        axes.add(
                new WarpAuthoringSnapshot.Axis("x-guid", new ParameterId("duplicate"), -1, 1, 3, List.of(3f, 1f, -1f)));
        if (xy)
            axes.add(new WarpAuthoringSnapshot.Axis(
                    "y-guid", new ParameterId("duplicate"), -2, 0, 2, List.of(2f, 0f, -2f)));
        var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
        var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
        for (float x : axes.get(0).keys())
            for (float y : xy ? axes.get(1).keys() : List.of(0f)) {
                String guid = "f" + x + ":" + y;
                var coordinates = new ArrayList<WarpAuthoringSnapshot.Coordinate>();
                coordinates.add(new WarpAuthoringSnapshot.Coordinate("x-guid", x));
                if (xy) coordinates.add(new WarpAuthoringSnapshot.Coordinate("y-guid", y));
                cells.add(new WarpAuthoringSnapshot.Cell(guid, coordinates));
                forms.add(new WarpAuthoringSnapshot.Form(
                        guid, x == 1 && y == 0 ? POINTS : Collections.nCopies(6, new Point2(99, 99))));
            }
        forms.add(new WarpAuthoringSnapshot.Form("unreferenced", POINTS));
        return new WarpAuthoringSnapshot(new DeformerId("warp"), "source", 1, 2, true, axes, cells, forms);
    }

    @Test
    void endpointsReproduceLandmarksAndOtherControlsInterpolateInNativeSpace() {
        var before = snapshot(false);
        var plan = plan(before, axes(false));
        var plus = form(plan.after(), 3, 0);
        assertEquals(new Point2(0, 0), plus.get(0));
        assertEquals(new Point2(2, 0), plus.get(1));
        assertEquals(new Point2(4, 0), plus.get(2));
        assertEquals(1f / 3, plus.get(3).x(), 1e-6);
        assertEquals(new Point2(-2, 0), form(plan.after(), -1, 0).get(0));
        assertEquals(new Point2(0, 0), form(plan.after(), -1, 0).get(1));
        assertEquals(before.axes(), plan.after().axes());
        assertEquals(before.cells(), plan.after().cells());
        assertEquals(before.rows(), plan.after().rows());
        assertEquals(before.columns(), plan.after().columns());
        assertEquals(before.quadTransform(), plan.after().quadTransform());
        assertEquals(POINTS, form(plan.after(), 1, 0));
        assertEquals(
                before.forms().get(before.forms().size() - 1),
                plan.after().forms().get(before.forms().size() - 1));
    }

    @Test
    void xyNineCellsAddFieldsAndDefaultIsNotFirstKeyOrCell() {
        var before = snapshot(true);
        var result = plan(before, axes(true));
        assertEquals(8, result.edits().size());
        assertEquals(new Point2(2, 3), form(result.after(), 3, 2).get(1));
        assertEquals(new Point2(0, -3), form(result.after(), -1, -2).get(1));
        assertEquals(new Point2(1, 3), form(result.after(), 1, 2).get(1));
        assertEquals(
                result.after(),
                plan(result.after(), axes(true)).after(),
                "Replanning must not accumulate displacement");
        var reversed = new ArrayList<>(axes(true));
        Collections.reverse(reversed);
        assertEquals(result.after(), plan(before, reversed).after());
    }

    @Test
    void interiorKeysUseFractionOfDeclaredEndpointRatherThanAbsoluteParameterValue() {
        var b = snapshot(false);
        var a = b.axes().get(0);
        var keys = List.of(-1f, 1f, 2f, 3f);
        var cells = new ArrayList<>(b.cells());
        cells.add(new WarpAuthoringSnapshot.Cell("middle", List.of(new WarpAuthoringSnapshot.Coordinate("x-guid", 2))));
        var forms = new ArrayList<>(b.forms());
        forms.add(new WarpAuthoringSnapshot.Form("middle", POINTS));
        var before = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.rows(),
                b.columns(),
                b.quadTransform(),
                List.of(new WarpAuthoringSnapshot.Axis(
                        a.parameterGuid(), a.parameterId(), a.minimum(), a.defaultValue(), a.maximum(), keys)),
                cells,
                forms);
        assertEquals(
                new Point2(1.5f, 0),
                form(plan(before, axes(false)).after(), 2, 0).get(1));
    }

    @Test
    void invalidAnchorsAndGuidMappingsFailBeforeProducingEdits() {
        var before = snapshot(false);
        for (var anchors : List.of(
                List.<McpLandmarkRigPlan.Anchor>of(),
                List.of(new McpLandmarkRigPlan.Anchor(-1, 0, 0)),
                List.of(new McpLandmarkRigPlan.Anchor(6, 0, 0)),
                List.of(new McpLandmarkRigPlan.Anchor(0, Double.NaN, 0)),
                List.of(new McpLandmarkRigPlan.Anchor(0, Double.MAX_VALUE, 0)),
                List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0), new McpLandmarkRigPlan.Anchor(0, 1, 0))))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> plan(before, List.of(new McpLandmarkRigPlan.Axis("x-guid", anchors, POSITIVE))));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(before, List.of(new McpLandmarkRigPlan.Axis("duplicate", NEGATIVE, POSITIVE))));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(
                        snapshot(true), List.of(axes(false).get(0), axes(false).get(0))));
    }

    @Test
    void sparseSharedAndAmbiguousGridsAreRejected() {
        var b = snapshot(true);
        assertThrows(
                IllegalArgumentException.class, () -> plan(replace(b, b.cells().subList(0, 8), b.forms()), axes(true)));
        var cells = new ArrayList<>(b.cells());
        cells.set(0, cells.get(1));
        var duplicate = replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> plan(duplicate, axes(true)));
        cells = new ArrayList<>(b.cells());
        cells.set(
                0,
                new WarpAuthoringSnapshot.Cell(
                        cells.get(1).formGuid(), cells.get(0).coordinates()));
        var shared = replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> plan(shared, axes(true)));
    }

    @Test
    void coincidentAnchorsAreRejectedAndFiniteFloatExtremesStayFinite() {
        var b = snapshot(false);
        var forms = b.forms().stream()
                .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), Collections.nCopies(6, new Point2(0, 0))))
                .toList();
        var coincident = replace(b, b.cells(), forms);
        assertThrows(IllegalArgumentException.class, () -> plan(coincident, axes(false)));
        var huge = List.of(
                new Point2(-Float.MAX_VALUE, 0),
                new Point2(0, 0),
                new Point2(Float.MAX_VALUE, 0),
                new Point2(0, 1),
                new Point2(1, 1),
                new Point2(2, 1));
        forms = b.forms().stream()
                .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), huge))
                .toList();
        var result = plan(
                replace(b, b.cells(), forms),
                List.of(new McpLandmarkRigPlan.Axis(
                        "x-guid",
                        List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0)),
                        List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0)))));
        assertTrue(result.after().forms().stream()
                .flatMap(f -> f.controlPoints().stream())
                .allMatch(p -> Float.isFinite(p.x())));
    }

    @Test
    void plannedPointBudgetAndFinalFloatOverflowAreRejected() {
        var b = snapshot(false);
        var large = Collections.nCopies(2050, new Point2(0, 0));
        var tooLarge = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                1024,
                false,
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), large))
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(
                        tooLarge,
                        List.of(new McpLandmarkRigPlan.Axis(
                                "x-guid",
                                List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0)),
                                List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0))))));
        var huge = Collections.nCopies(6, new Point2(Float.MAX_VALUE, 0));
        var overflow = replace(
                b,
                b.cells(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), huge))
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(
                        overflow,
                        List.of(new McpLandmarkRigPlan.Axis(
                                "x-guid",
                                List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0)),
                                List.of(new McpLandmarkRigPlan.Anchor(0, Float.MAX_VALUE, 0))))));
    }

    @Test
    void displacementInterpolationKeepsDoublePrecisionUntilFinalNativePosition() {
        var b = snapshot(false);
        var reference = Collections.nCopies(6, new Point2(-1, 0));
        var before = replace(
                b,
                b.cells(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), reference))
                        .toList());
        var result = plan(
                before,
                List.of(new McpLandmarkRigPlan.Axis(
                        "x-guid",
                        List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0)),
                        List.of(new McpLandmarkRigPlan.Anchor(0, 1.00000001, 0)))));
        assertEquals((float) (1.00000001 - 1), form(result.after(), 3, 0).get(0).x());
        assertNotEquals(0, form(result.after(), 3, 0).get(0).x());
    }

    @Test
    void explicitCornerPreservesOtherQuadrantsAxesAndStableReplanning() {
        var b = snapshot(true);
        var base = plan(b, axes(true)).after();
        var corner = new McpLandmarkRigPlan.Corner(
                Map.of("x-guid", true, "y-guid", true), List.of(new McpLandmarkRigPlan.Anchor(1, 4, 8)));
        var corrected = McpKeyformWritePlan.prepare(b, McpWarpRigPlan.edits(b, axes(true), List.of(corner)));
        var after = (WarpAuthoringSnapshot) corrected.after();
        assertEquals(new Point2(6, 11), form(after, 3, 2).get(1));
        for (float x : List.of(-1f, 1f, 3f))
            for (float y : List.of(-2f, 0f, 2f)) {
                if (x == 3 && y == 2) continue;
                assertEquals(form(base, x, y), form(after, x, y));
            }
        assertEquals(
                after,
                McpKeyformWritePlan.prepare(after, McpWarpRigPlan.edits(after, axes(true), List.of(corner)))
                        .after());
    }

    @Test
    void interiorCornerWeightIsProductOfAxisFractions() {
        var b = snapshot(true);
        var xKeys = List.of(-1f, 1f, 2f, 3f);
        var yKeys = List.of(-2f, 0f, 1f, 2f);
        var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
        var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
        for (float x : xKeys)
            for (float y : yKeys) {
                String guid = "interior" + x + ":" + y;
                cells.add(new WarpAuthoringSnapshot.Cell(
                        guid,
                        List.of(
                                new WarpAuthoringSnapshot.Coordinate("x-guid", x),
                                new WarpAuthoringSnapshot.Coordinate("y-guid", y))));
                forms.add(new WarpAuthoringSnapshot.Form(guid, POINTS));
            }
        var before = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.rows(),
                b.columns(),
                b.quadTransform(),
                List.of(
                        new WarpAuthoringSnapshot.Axis("x-guid", new ParameterId("duplicate"), -1, 1, 3, xKeys),
                        new WarpAuthoringSnapshot.Axis("y-guid", new ParameterId("duplicate"), -2, 0, 2, yKeys)),
                cells,
                forms);
        var c = new McpLandmarkRigPlan.Corner(
                Map.of("x-guid", true, "y-guid", true), List.of(new McpLandmarkRigPlan.Anchor(1, 4, 8)));
        var after = (WarpAuthoringSnapshot)
                McpKeyformWritePlan.prepare(before, McpWarpRigPlan.edits(before, axes(true), List.of(c)))
                        .after();
        assertEquals(new Point2(2.5f, 3.5f), form(after, 2, 1).get(1));
    }

    @Test
    void fourQuadrantsAreIndependentAndDirectionsMustCoverBothGuids() {
        var b = snapshot(true);
        var corners = new ArrayList<McpLandmarkRigPlan.Corner>();
        int index = 1;
        for (boolean x : List.of(false, true))
            for (boolean y : List.of(false, true))
                corners.add(new McpLandmarkRigPlan.Corner(
                        Map.of("x-guid", x, "y-guid", y), List.of(new McpLandmarkRigPlan.Anchor(0, index, -index++))));
        var after = McpKeyformWritePlan.prepare(b, McpWarpRigPlan.edits(b, axes(true), corners))
                .after();
        Collections.reverse(corners);
        var reverseAxes = new ArrayList<>(axes(true));
        Collections.reverse(reverseAxes);
        assertEquals(
                after,
                McpKeyformWritePlan.prepare(b, McpWarpRigPlan.edits(b, reverseAxes, corners))
                        .after());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpRigPlan.edits(b, axes(true), List.of(corners.get(0), corners.get(0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpRigPlan.edits(snapshot(false), axes(false), List.of(corners.get(0))));
        for (var directions : List.of(Map.of("x-guid", true), Map.of("x-guid", true, "unknown-guid", false)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpWarpRigPlan.edits(
                            b,
                            axes(true),
                            List.of(new McpLandmarkRigPlan.Corner(
                                    directions, List.of(new McpLandmarkRigPlan.Anchor(0, 1, 1))))));
    }

    static WarpAuthoringSnapshot replace(
            WarpAuthoringSnapshot b, List<WarpAuthoringSnapshot.Cell> cells, List<WarpAuthoringSnapshot.Form> forms) {
        return new WarpAuthoringSnapshot(
                b.deformerId(), b.sourceGuid(), b.rows(), b.columns(), b.quadTransform(), b.axes(), cells, forms);
    }

    private static McpKeyformWritePlan.Warp plan(WarpAuthoringSnapshot b, List<McpLandmarkRigPlan.Axis> axes) {
        return (McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(b, McpWarpRigPlan.edits(b, axes));
    }

    private static List<Point2> form(WarpAuthoringSnapshot b, float x, float y) {
        String guid = b.cells().stream()
                .filter(c -> c.coordinates().get(0).value() == x
                        && (c.coordinates().size() == 1
                                || c.coordinates().get(1).value() == y))
                .findFirst()
                .orElseThrow()
                .formGuid();
        return b.forms().stream()
                .filter(f -> f.formGuid().equals(guid))
                .findFirst()
                .orElseThrow()
                .controlPoints();
    }
}
