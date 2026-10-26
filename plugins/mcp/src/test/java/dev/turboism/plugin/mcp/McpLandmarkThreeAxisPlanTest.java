package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpLandmarkThreeAxisPlanTest {
    @Test
    void threeAxisFieldsAndPairTripleResidualsUseOnlyExplicitGuidDirections() {
        var b = grid(3, 4);
        var corrections = List.of(
                corner(Map.of("g0", true, "g1", false), 8, 4),
                corner(Map.of("g2", true, "g0", true, "g1", false), 16, 8));
        var input = axes(3);
        Collections.reverse(input);
        var warpEdits = McpWarpRigPlan.edits(b, input, corrections);
        var after = ((McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(b, warpEdits)).after();
        var mesh = mesh(b);
        var meshEdits = McpMeshRigPlan.edits(mesh, input, corrections);
        var meshAfter = ((McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(mesh, meshEdits)).after();
        assertEquals(63, warpEdits.size());
        assertEquals(b.axes(), after.axes());
        assertEquals(b.cells(), after.cells());
        assertEquals(b.rows(), after.rows());
        assertEquals(b.columns(), after.columns());
        assertEquals(b.quadTransform(), after.quadTransform());
        assertEquals(mesh.sourceGeometry(), meshAfter.sourceGeometry());
        assertEquals(mesh.axes(), meshAfter.axes());
        assertEquals(mesh.cells(), meshAfter.cells());
        assertEquals(mesh.morphKeys(), meshAfter.morphKeys());
        for (var cell : b.cells()) {
            double dx = 0, dy = 0;
            var normalized = new HashMap<String, Double>();
            for (var c : cell.coordinates()) {
                int index = Integer.parseInt(c.parameterGuid().substring(1));
                var axis = b.axes().get(index);
                double delta = c.value() - axis.defaultValue();
                double ratio = delta
                        / (delta >= 0 ? axis.maximum() - axis.defaultValue() : axis.defaultValue() - axis.minimum());
                normalized.put(c.parameterGuid(), ratio);
                dx += ratio * (index + 1);
                dy += ratio * (index + 1) * 2;
            }
            double pair = Math.max(0, normalized.get("g0")) * Math.max(0, -normalized.get("g1"));
            double triple = pair * Math.max(0, normalized.get("g2"));
            dx += pair * 8 + triple * 16;
            dy += pair * 4 + triple * 8;
            var original = form(b, "default");
            var points = form(after, cell.formGuid());
            var meshForm = meshAfter.forms().stream()
                    .filter(f -> f.formGuid().equals(cell.formGuid()))
                    .findFirst()
                    .orElseThrow();
            for (int i = 0; i < points.size(); i++)
                assertEquals(
                        new Point2((float) (original.get(i).x() + dx), (float)
                                (original.get(i).y() + dy)),
                        points.get(i));
            assertEquals(points, meshForm.positions());
        }
        for (int i = 0; i < mesh.forms().size(); i++) {
            assertEquals(
                    mesh.forms().get(i).drawOrder(), meshAfter.forms().get(i).drawOrder());
            assertEquals(mesh.forms().get(i).opacity(), meshAfter.forms().get(i).opacity());
        }
        for (String guid : List.of("default", "unused")) {
            assertEquals(form(b, guid), form(after, guid));
            assertEquals(
                    mesh.forms().stream().filter(f -> f.formGuid().equals(guid)).toList(),
                    meshAfter.forms().stream()
                            .filter(f -> f.formGuid().equals(guid))
                            .toList());
        }
        assertEquals(warpEdits, McpWarpRigPlan.edits(after, axes(3), corrections));
        assertEquals(meshEdits, McpMeshRigPlan.edits(meshAfter, axes(3), corrections));
    }

    @Test
    void allTwentyUniqueResidualsAreAcceptedAndEachEndpointUsesItsThreePairsAndOctant() {
        var b = grid(3, 3);
        var corners = allCorners();
        assertEquals(20, corners.size());
        var neutral = axes(3).stream()
                .map(a -> new McpLandmarkRigPlan.Axis(a.guid(), anchors(0, 0), anchors(0, 0)))
                .toList();
        var after = (WarpAuthoringSnapshot) McpKeyformWritePlan.prepare(b, McpWarpRigPlan.edits(b, neutral, corners))
                .after();
        for (var cell : b.cells()) {
            long active = cell.coordinates().stream()
                    .filter(c -> c.value()
                            != b.axes()
                                    .get(Integer.parseInt(c.parameterGuid().substring(1)))
                                    .defaultValue())
                    .count();
            int residual = active == 3 ? 4 : active == 2 ? 1 : 0;
            assertEquals(new Point2(residual, 0), form(after, cell.formGuid()).get(0));
        }
    }

    @Test
    void residualIdentityMustBeUniqueAndReferenceTwoOrThreeBoundAxes() {
        var b = grid(3, 3);
        for (var sides : List.of(
                Map.of("g0", true),
                Map.of("g0", true, "unknown", false),
                Map.of("g0", true, "g1", true, "g2", true, "g3", true))) {
            var invalid = List.of(corner(sides, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> McpWarpRigPlan.edits(b, axes(3), invalid));
            assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(mesh(b), axes(3), invalid));
        }
        var duplicate = new ArrayList<>(allCorners());
        duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> McpWarpRigPlan.edits(b, axes(3), duplicate));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpRigPlan.edits(b, axes(3), List.of(duplicate.get(0), duplicate.get(0))));
    }

    @Test
    void thirdAxisDoesNotRelaxGridCompletenessSharedFormOrGuidCoverageGuards() {
        var b = grid(3, 3);
        assertThrows(UnsupportedOperationException.class, () -> McpWarpRigPlan.edits(b, axes(2)));
        var cells = new ArrayList<>(b.cells());
        cells.remove(0);
        var sparse = McpWarpRigPlanTest.replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> McpWarpRigPlan.edits(sparse, axes(3)));
        cells = new ArrayList<>(b.cells());
        cells.set(
                0,
                new WarpAuthoringSnapshot.Cell(
                        cells.get(1).formGuid(), cells.get(0).coordinates()));
        var shared = McpWarpRigPlanTest.replace(b, cells, b.forms());
        assertThrows(IllegalArgumentException.class, () -> McpWarpRigPlan.edits(shared, axes(3)));
        assertThrows(IllegalArgumentException.class, () -> McpMeshRigPlan.edits(mesh(shared), axes(3)));
        assertThrows(IllegalArgumentException.class, () -> McpWarpRigPlan.edits(grid(3, 5), axes(3)));
        assertThrows(UnsupportedOperationException.class, () -> McpWarpRigPlan.edits(grid(4, 3), axes(4)));
    }

    static WarpAuthoringSnapshot grid(int dimensions, int width) {
        var axes = new ArrayList<WarpAuthoringSnapshot.Axis>();
        for (int i = 0; i < dimensions; i++) {
            float center = i == 0 ? 1 : i == 2 ? 10 : 0;
            float step = i == 2 ? 4 : 2;
            var keys = new ArrayList<Float>(List.of(center - step, center, center + step));
            if (width >= 4) keys.add(center + step / 2);
            if (width >= 5) keys.add(center - step / 2);
            axes.add(new WarpAuthoringSnapshot.Axis(
                    "g" + i, new ParameterId("same-display-id"), center - step, center, center + step, keys));
        }
        var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
        var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
        var points = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        for (int k = 0; k < (int) Math.pow(width, dimensions); k++) {
            int code = k;
            boolean defaults = true;
            var coordinates = new ArrayList<WarpAuthoringSnapshot.Coordinate>();
            for (var axis : axes) {
                float key = axis.keys().get(code % width);
                code /= width;
                defaults &= key == axis.defaultValue();
                coordinates.add(new WarpAuthoringSnapshot.Coordinate(axis.parameterGuid(), key));
            }
            Collections.reverse(coordinates);
            String id = defaults ? "default" : "f" + k;
            cells.add(new WarpAuthoringSnapshot.Cell(id, coordinates));
            forms.add(
                    new WarpAuthoringSnapshot.Form(id, defaults ? points : Collections.nCopies(4, new Point2(99, 99))));
        }
        forms.add(new WarpAuthoringSnapshot.Form("unused", points));
        Collections.reverse(cells);
        return new WarpAuthoringSnapshot(new DeformerId("warp"), "source", 1, 1, true, axes, cells, forms);
    }

    static ArtMeshAuthoringSnapshot mesh(WarpAuthoringSnapshot warp) {
        var b = McpMeshRigPlanTest.fromWarp(warp);
        return new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                f.formGuid(), f.positions(), f.drawOrder(), Optional.of(.5f)))
                        .toList(),
                b.morphKeys());
    }

    static ArrayList<McpLandmarkRigPlan.Axis> axes(int dimensions) {
        var out = new ArrayList<McpLandmarkRigPlan.Axis>();
        for (int i = 0; i < dimensions; i++)
            out.add(new McpLandmarkRigPlan.Axis("g" + i, anchors(-i - 1, -2 * (i + 1)), anchors(i + 1, 2 * (i + 1))));
        return out;
    }

    static ArrayList<McpLandmarkRigPlan.Corner> allCorners() {
        var out = new ArrayList<McpLandmarkRigPlan.Corner>();
        for (int mask = 1; mask < 8; mask++) {
            int n = Integer.bitCount(mask);
            if (n < 2) continue;
            for (int sides = 0; sides < (1 << n); sides++) {
                var directions = new HashMap<String, Boolean>();
                int j = 0;
                for (int i = 0; i < 3; i++)
                    if ((mask & (1 << i)) != 0) directions.put("g" + i, (sides & (1 << j++)) != 0);
                out.add(corner(Map.copyOf(directions), 1, 0));
            }
        }
        return out;
    }

    static List<Map<String, Object>> axisJson(String indexName) {
        return axes(3).stream()
                .map(a -> Map.<String, Object>of(
                        "parameterGuid",
                        a.guid(),
                        "negativeAnchors",
                        List.of(Map.of(
                                indexName,
                                0,
                                "dx",
                                a.negative().get(0).dx(),
                                "dy",
                                a.negative().get(0).dy())),
                        "positiveAnchors",
                        List.of(Map.of(
                                indexName,
                                0,
                                "dx",
                                a.positive().get(0).dx(),
                                "dy",
                                a.positive().get(0).dy()))))
                .toList();
    }

    static List<Map<String, Object>> cornerJson(String indexName) {
        return allCorners().stream()
                .map(c -> Map.<String, Object>of(
                        "directions",
                        c.positiveSides().entrySet().stream()
                                .map(e -> Map.of(
                                        "parameterGuid", e.getKey(), "side", e.getValue() ? "POSITIVE" : "NEGATIVE"))
                                .toList(),
                        "anchors",
                        List.of(Map.of(indexName, 0, "dx", 1, "dy", 0))))
                .toList();
    }

    private static McpLandmarkRigPlan.Corner corner(Map<String, Boolean> directions, double x, double y) {
        return new McpLandmarkRigPlan.Corner(directions, anchors(x, y));
    }

    private static List<McpLandmarkRigPlan.Anchor> anchors(double x, double y) {
        return List.of(new McpLandmarkRigPlan.Anchor(0, x, y));
    }

    private static List<Point2> form(WarpAuthoringSnapshot b, String guid) {
        return b.forms().stream()
                .filter(f -> f.formGuid().equals(guid))
                .findFirst()
                .orElseThrow()
                .controlPoints();
    }
}
