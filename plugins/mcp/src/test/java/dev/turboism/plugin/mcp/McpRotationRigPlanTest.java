package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpRotationRigPlanTest {
    @Test
    void combinedAnglesUseGuidTuplesPreserveOtherValuesAndDoNotAccumulate() {
        for (int n : List.of(1, 2, 3)) {
            var before = grid(n, 3);
            var gains = gains(n);
            Collections.reverse(gains);
            var edits = McpRotationRigPlan.edits(before, gains);
            assertEquals(before.cells().size() - 1, edits.size());
            var after = (RotationAuthoringSnapshot)
                    McpKeyformWritePlan.prepare(before, edits).after();
            assertEquals(before.axes(), after.axes());
            assertEquals(before.cells(), after.cells());
            assertEquals(before.baseAngle(), after.baseAngle());
            for (var cell : before.cells()) {
                var old = form(before, cell.formGuid());
                var next = form(after, cell.formGuid());
                double expected = 7;
                for (var coordinate : cell.coordinates()) {
                    int index = Integer.parseInt(coordinate.parameterGuid().substring(1));
                    expected += (coordinate.value() - before.axes().get(index).defaultValue()) * (index + 1);
                }
                assertEquals((float) expected, next.angle());
                assertEquals(old.origin(), next.origin());
                assertEquals(old.scale(), next.scale());
                assertEquals(old.reflectedX(), next.reflectedX());
                assertEquals(old.reflectedY(), next.reflectedY());
            }
            assertEquals(form(before, "default"), form(after, "default"));
            assertEquals(
                    before.forms().get(before.forms().size() - 1),
                    after.forms().get(after.forms().size() - 1));
            assertEquals(
                    after,
                    McpKeyformWritePlan.prepare(after, McpRotationRigPlan.edits(after, gains))
                            .after());
            assertEquals(edits, McpRotationRigPlan.edits(before, gains(n)));
        }
    }

    @Test
    void legacySingleAxisAndNewSingleAxisAgreeAndNeverSelectSubset() {
        var single = grid(1, 3);
        assertEquals(McpRotationRigPlan.edits(single, "g0", 1), McpRotationRigPlan.edits(single, gains(1)));
        assertThrows(UnsupportedOperationException.class, () -> McpRotationRigPlan.edits(grid(2, 3), "g0", 1));
    }

    @Test
    void rejectsMissingExtraDuplicateAndUnknownAxes() {
        var b = grid(2, 3);
        assertThrows(UnsupportedOperationException.class, () -> McpRotationRigPlan.edits(b, gains(1)));
        assertThrows(UnsupportedOperationException.class, () -> McpRotationRigPlan.edits(b, gains(3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        b, List.of(new McpRotationRigPlan.Axis("g0", 1), new McpRotationRigPlan.Axis("g0", 2))));
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpRotationRigPlan.edits(
                        b,
                        List.of(new McpRotationRigPlan.Axis("g0", 1), new McpRotationRigPlan.Axis("display-id", 2))));
        assertThrows(UnsupportedOperationException.class, () -> McpRotationRigPlan.edits(grid(4, 2), gains(4)));
    }

    @Test
    void rejectsHolesDuplicateTuplesAndSharedDefaultOrNondefaultForms() {
        var b = grid(2, 3);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(copy(b, b.axes(), b.cells().subList(0, 8)), gains(2)));
        for (int replacement : List.of(0, 1)) {
            var cells = new ArrayList<>(b.cells());
            cells.set(
                    8,
                    new RotationAuthoringSnapshot.Cell(
                            cells.get(replacement).formGuid(), cells.get(8).coordinates()));
            assertThrows(
                    IllegalArgumentException.class, () -> McpRotationRigPlan.edits(copy(b, b.axes(), cells), gains(2)));
        }
        var cells = new ArrayList<>(b.cells());
        cells.set(
                8,
                new RotationAuthoringSnapshot.Cell(
                        cells.get(8).formGuid(), cells.get(7).coordinates()));
        assertThrows(
                IllegalArgumentException.class, () -> McpRotationRigPlan.edits(copy(b, b.axes(), cells), gains(2)));
    }

    @Test
    void rejectsDuplicateSignedZeroOutOfRangeAndMissingDefaultKeys() {
        var b = grid(2, 3);
        var axis = b.axes().get(0);
        for (var keys : List.of(List.of(0f, 0f, 2f), List.of(0f, -0f, 2f), List.of(0f, 2f, 3f))) {
            var a = new RotationAuthoringSnapshot.Axis("g0", axis.parameterId(), -1, 1, 2, keys);
            // Use no cells: axis validation must reject before cardinality or any native access.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpRotationRigPlan.edits(copy(b, List.of(a, b.axes().get(1)), List.of()), gains(2)));
        }
    }

    @Test
    void limitsCartesianGridAndRejectsNonfiniteGainsAndNativeFloatOverflow() {
        assertEquals(63, McpRotationRigPlan.edits(grid(3, 4), gains(3)).size());
        assertThrows(IllegalArgumentException.class, () -> McpRotationRigPlan.edits(grid(3, 5), gains(3)));
        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException.class, () -> new McpRotationRigPlan.Axis("g0", value));
        var b = grid(2, 3);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        b,
                        List.of(
                                new McpRotationRigPlan.Axis("g0", Double.MAX_VALUE),
                                new McpRotationRigPlan.Axis("g1", 0))));
        var zero = List.of(new McpRotationRigPlan.Axis("g0", 0), new McpRotationRigPlan.Axis("g1", 0));
        var after = (RotationAuthoringSnapshot) McpKeyformWritePlan.prepare(b, McpRotationRigPlan.edits(b, zero))
                .after();
        assertTrue(after.forms().stream()
                .filter(f -> !f.formGuid().equals("unused"))
                .allMatch(f -> f.value().angle() == 7));
    }

    static ArrayList<McpRotationRigPlan.Axis> gains(int n) {
        var out = new ArrayList<McpRotationRigPlan.Axis>();
        for (int i = 0; i < n; i++) out.add(new McpRotationRigPlan.Axis("g" + i, i + 1));
        return out;
    }

    static RotationAuthoringSnapshot grid(int n, int width) {
        var axes = new ArrayList<RotationAuthoringSnapshot.Axis>();
        for (int i = 0; i < n; i++) {
            var keys = new ArrayList<Float>();
            for (int k = 0; k < width; k++) keys.add((float) k);
            // Nonzero default on axis 0 detects incorrect absolute-key summation.
            axes.add(new RotationAuthoringSnapshot.Axis(
                    "g" + i, new ParameterId("same-display-id"), 0, i == 0 ? 1 : 0, width - 1, keys));
        }
        int count = (int) Math.pow(width, n);
        var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
        var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
        for (int k = 0; k < count; k++) {
            int value = k;
            boolean defaults = true;
            var coordinates = new ArrayList<RotationAuthoringSnapshot.Coordinate>();
            for (int i = 0; i < n; i++) {
                float key = value % width;
                value /= width;
                defaults &= key == axes.get(i).defaultValue();
                coordinates.add(new RotationAuthoringSnapshot.Coordinate("g" + i, key));
            }
            Collections.reverse(coordinates);
            String id = defaults ? "default" : "f" + k;
            cells.add(new RotationAuthoringSnapshot.Cell(id, coordinates));
            forms.add(new RotationAuthoringSnapshot.Form(
                    id, new RotationDeformerForm(defaults ? 7 : 90, k + 1, k + 2, k + 1, k % 2 == 0, k % 3 == 0)));
        }
        // Arbitrary cell order is allowed; unrelated form is preserved, not treated as a target.
        Collections.reverse(cells);
        forms.add(new RotationAuthoringSnapshot.Form("unused", new RotationDeformerForm(91, 0, 0, 1, false, false)));
        return new RotationAuthoringSnapshot(new DeformerId("rotation"), "source", 19, axes, cells, forms);
    }

    private static RotationDeformerForm form(RotationAuthoringSnapshot snapshot, String guid) {
        return snapshot.forms().stream()
                .filter(f -> f.formGuid().equals(guid))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static RotationAuthoringSnapshot copy(
            RotationAuthoringSnapshot b,
            List<RotationAuthoringSnapshot.Axis> axes,
            List<RotationAuthoringSnapshot.Cell> cells) {
        return new RotationAuthoringSnapshot(b.deformerId(), b.sourceGuid(), b.baseAngle(), axes, cells, b.forms());
    }
}
