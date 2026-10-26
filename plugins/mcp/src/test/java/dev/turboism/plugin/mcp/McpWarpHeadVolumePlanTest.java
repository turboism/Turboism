package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpWarpHeadVolumePlanTest {
    static final McpWarpHeadVolumePlan.Profile PROFILE = new McpWarpHeadVolumePlan.Profile(0, 0, 1, 1, 2, 10);

    static List<McpWarpHeadVolumePlan.Axis> axes(double yaw, double pitch, double roll) {
        return List.of(
                new McpWarpHeadVolumePlan.Axis("g0", McpWarpHeadVolumePlan.Component.YAW, yaw),
                new McpWarpHeadVolumePlan.Axis("g1", McpWarpHeadVolumePlan.Component.PITCH, pitch),
                new McpWarpHeadVolumePlan.Axis("g2", McpWarpHeadVolumePlan.Component.ROLL, roll));
    }

    static Map<String, Object> profileJson() {
        return Map.of("centerX", 0, "centerY", 0, "radiusX", 1, "radiusY", 1, "depth", 2, "cameraDistance", 10);
    }

    static List<Map<String, Object>> axisJson() {
        return axes(15, 10, 5).stream()
                .map(a -> Map.<String, Object>of(
                        "parameterGuid",
                        a.parameterGuid(),
                        "component",
                        a.component().name(),
                        "degreesPerParameterUnit",
                        a.degreesPerParameterUnit()))
                .toList();
    }

    @Test
    void centerYawAndPitchMatchAnalyticPerspectiveAndRollUsesMathematicalPositiveZ() {
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        var yaw = after(b, axes(15, 0, 0), PROFILE);
        double center = 10 * 2 * .5 / (10 - 2 * Math.cos(Math.PI / 6));
        assertEquals(center, points(yaw, 3, 0, 10).get(0).x(), 1e-6);
        assertEquals(-center, points(yaw, -1, 0, 10).get(0).x(), 1e-6);
        assertEquals(0, points(yaw, 3, 0, 10).get(0).y());
        var pitch = after(b, axes(0, 15, 0), PROFILE);
        assertEquals(-center, points(pitch, 1, 2, 10).get(0).y(), 1e-6);
        assertEquals(center, points(pitch, 1, -2, 10).get(0).y(), 1e-6);
        // A zero-depth front plane at +90 roll maps (1,0) to (0,1), no perspective scaling.
        var plane = new McpWarpHeadVolumePlan.Profile(0, 0, 1, 1, 0, 10);
        var roll = after(b, axes(0, 0, 22.5), plane);
        assertEquals(0, points(roll, 1, 0, 14).get(1).x(), 1e-6);
        assertEquals(1, points(roll, 1, 0, 14).get(1).y(), 1e-6);
        // Yaw then roll: the yaw-projected center is subsequently rotated toward positive y.
        var combined = after(b, axes(15, 0, 22.5), PROFILE);
        assertEquals(0, points(combined, 3, 0, 14).get(0).x(), 1e-6);
        assertEquals(center, points(combined, 3, 0, 14).get(0).y(), 1e-6);
        var three = after(b, axes(15, 15, 22.5), PROFILE);
        // (0,0,2) -> yaw30: (1,0,sqrt(3)); pitch30: (1,-sqrt(3)/2,1.5);
        // roll90: (sqrt(3)/2,1,1.5); perspective distance is 8.5.
        assertEquals(10 * Math.sqrt(3) / 2 / 8.5, points(three, 3, 2, 14).get(0).x(), 1e-6);
        assertEquals(10 / 8.5, points(three, 3, 2, 14).get(0).y(), 1e-6);
    }

    @Test
    void gridDefaultAndUnrelatedFormsArePreservedAndReplanningDoesNotAccumulate() {
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 4);
        var edits = McpWarpHeadVolumePlan.edits(b, axes(15, 10, 5), PROFILE);
        assertEquals(63, edits.size());
        var next = (WarpAuthoringSnapshot) McpKeyformWritePlan.prepare(b, edits).after();
        assertEquals(b.deformerId(), next.deformerId());
        assertEquals(b.sourceGuid(), next.sourceGuid());
        assertEquals(b.rows(), next.rows());
        assertEquals(b.columns(), next.columns());
        assertEquals(b.quadTransform(), next.quadTransform());
        assertEquals(b.axes(), next.axes());
        assertEquals(b.cells(), next.cells());
        for (String guid : List.of("default", "unused"))
            assertEquals(
                    b.forms().stream().filter(f -> f.formGuid().equals(guid)).toList(),
                    next.forms().stream().filter(f -> f.formGuid().equals(guid)).toList());
        assertEquals(edits, McpWarpHeadVolumePlan.edits(next, axes(15, 10, 5), PROFILE));
        var reverse = new ArrayList<>(axes(15, 10, 5));
        Collections.reverse(reverse);
        assertEquals(edits, McpWarpHeadVolumePlan.edits(b, reverse, PROFILE));
        var neutral = after(b, axes(0, 0, 0), PROFILE);
        var defaultPoints = points(neutral, 1, 0, 10);
        assertTrue(neutral.forms().stream()
                .filter(f -> !f.formGuid().equals("unused"))
                .allMatch(f -> f.controlPoints().equals(defaultPoints)));
    }

    @Test
    void profileGainsAnglesNearPlaneAndNativeFloatLimitsFailClosed() {
        for (var profile : List.of(
                new double[] {0, 1, 2, 10},
                new double[] {1, -1, 2, 10},
                new double[] {1, 1, -1, 10},
                new double[] {1, 1, 10, 10},
                new double[] {1, 1, Double.NaN, 10}))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new McpWarpHeadVolumePlan.Profile(0, 0, profile[0], profile[1], profile[2], profile[3]));
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        // Reject a near-camera profile even when a zero gain would avoid projecting any point.
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpHeadVolumePlan.edits(
                        b, axes(0, 0, 0), new McpWarpHeadVolumePlan.Profile(0, 0, 1, 1, 9.99, 10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpWarpHeadVolumePlan.Axis(
                        "g0", McpWarpHeadVolumePlan.Component.YAW, Double.POSITIVE_INFINITY));
        for (var input : List.of(axes(43, 0, 0), axes(0, 43, 0), axes(0, 0, 46), axes(Double.MAX_VALUE, 0, 0)))
            assertThrows(IllegalArgumentException.class, () -> McpWarpHeadVolumePlan.edits(b, input, PROFILE));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpHeadVolumePlan.edits(
                        b, axes(15, 0, 0), new McpWarpHeadVolumePlan.Profile(0, 0, 1, 1, 9.99, 10)));
        var far = withPoints(b, Collections.nCopies(4, new Point2(-100, 0)));
        assertThrows(IllegalArgumentException.class, () -> McpWarpHeadVolumePlan.edits(far, axes(40, 0, 0), PROFILE));
        var huge = withPoints(b, Collections.nCopies(4, new Point2(Float.MAX_VALUE, Float.MAX_VALUE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpHeadVolumePlan.edits(
                        huge, axes(0, 0, 11.25), new McpWarpHeadVolumePlan.Profile(0, 0, 1, 1, 0, 1e100)));
    }

    @Test
    void missingAxesDuplicateComponentsSharedAndIncompleteGridsRemainRejected() {
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpWarpHeadVolumePlan.edits(b, axes(1, 1, 1).subList(0, 2), PROFILE));
        var duplicate = List.of(
                new McpWarpHeadVolumePlan.Axis("g0", McpWarpHeadVolumePlan.Component.YAW, 1),
                new McpWarpHeadVolumePlan.Axis("g1", McpWarpHeadVolumePlan.Component.YAW, 1),
                axes(1, 1, 1).get(2));
        assertThrows(IllegalArgumentException.class, () -> McpWarpHeadVolumePlan.edits(b, duplicate, PROFILE));
        var cells = new ArrayList<>(b.cells());
        cells.remove(0);
        var sparse = McpWarpRigPlanTest.replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> McpWarpHeadVolumePlan.edits(sparse, axes(1, 1, 1), PROFILE));
        cells = new ArrayList<>(b.cells());
        cells.set(
                0,
                new WarpAuthoringSnapshot.Cell(
                        cells.get(1).formGuid(), cells.get(0).coordinates()));
        var shared = McpWarpRigPlanTest.replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> McpWarpHeadVolumePlan.edits(shared, axes(1, 1, 1), PROFILE));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpHeadVolumePlan.edits(McpLandmarkThreeAxisPlanTest.grid(3, 5), axes(1, 1, 1), PROFILE));
    }

    private static WarpAuthoringSnapshot withPoints(WarpAuthoringSnapshot b, List<Point2> points) {
        return McpWarpRigPlanTest.replace(
                b,
                b.cells(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), points))
                        .toList());
    }

    private static WarpAuthoringSnapshot after(
            WarpAuthoringSnapshot b, List<McpWarpHeadVolumePlan.Axis> input, McpWarpHeadVolumePlan.Profile profile) {
        return (WarpAuthoringSnapshot) McpKeyformWritePlan.prepare(b, McpWarpHeadVolumePlan.edits(b, input, profile))
                .after();
    }

    private static List<Point2> points(WarpAuthoringSnapshot b, float x, float y, float z) {
        var tuple = Map.of("g0", x, "g1", y, "g2", z);
        String guid = b.cells().stream()
                .filter(c -> c.coordinates().stream().allMatch(v -> v.value() == tuple.get(v.parameterGuid())))
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
