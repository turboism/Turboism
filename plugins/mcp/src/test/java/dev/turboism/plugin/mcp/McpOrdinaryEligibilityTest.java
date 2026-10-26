package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpOrdinaryEligibilityTest {
    @Test
    void defaultIndependentAndUnreferencedFormsHaveDistinctReasons() {
        var b = McpRotationRigDomainTest.snapshot(7);
        var forms = new ArrayList<>(b.forms());
        forms.add(new RotationAuthoringSnapshot.Form("orphan", b.forms().get(0).value()));
        var snapshot = rotation(b.axes(), b.cells(), forms);
        reason(snapshot, "default", false, "DEFAULT_FORM");
        reason(snapshot, "minus", true, "INDEPENDENT_NON_DEFAULT_ORDINARY");
        reason(snapshot, "plus", true, "INDEPENDENT_NON_DEFAULT_ORDINARY");
        reason(snapshot, "orphan", false, "NO_ORDINARY_CELL");
    }

    @Test
    void sharedTargetsAndMalformedDefaultAreRejectedConsistentlyWithWriter() {
        var b = McpRotationRigDomainTest.snapshot(7);
        var shared = new ArrayList<>(b.cells());
        shared.set(
                2, new RotationAuthoringSnapshot.Cell("minus", b.cells().get(2).coordinates()));
        var snapshot = rotation(b.axes(), shared, b.forms());
        reason(snapshot, "minus", false, "SHARED_FORM");
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(
                        snapshot,
                        List.of(edit(b.cells().get(1), b.forms().get(1).value()))));
        shared.set(
                2,
                new RotationAuthoringSnapshot.Cell("default", b.cells().get(2).coordinates()));
        var defaultShared = rotation(b.axes(), shared, b.forms());
        reason(defaultShared, "minus", false, "SHARED_OR_MORPH_DEFAULT_FORM");
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(
                        defaultShared,
                        List.of(edit(b.cells().get(1), b.forms().get(1).value()))));
    }

    @Test
    void exactDefaultsAndUniqueCellsAreRequiredForAllTargets() {
        var b = McpRotationRigDomainTest.snapshot(7);
        var noKey = List.of(new RotationAuthoringSnapshot.Axis(
                "axis-guid", new ParameterId("display-id"), -20, 1, 20, List.of(-20f, 0f, 20f)));
        reason(rotation(noKey, b.cells(), b.forms()), "minus", false, "MISSING_DEFAULT_KEY_OR_AMBIGUOUS_AXIS");
        reason(rotation(b.axes(), b.cells().subList(1, 3), b.forms()), "minus", false, "MISSING_EXACT_DEFAULT_CELL");
        var duplicate = new ArrayList<>(b.cells());
        duplicate.add(b.cells().get(1));
        reason(rotation(b.axes(), duplicate, b.forms()), "plus", false, "DUPLICATE_CELL_TUPLE");
    }

    @Test
    void artMeshMorphAndSharedReferencesDoNotBecomeOrdinaryWritable() {
        var points = List.of(new Point2(0, 0));
        var b = McpRotationRigDomainTest.snapshot(7);
        var mesh = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source",
                new ArtMeshGeometry(points, points, List.of()),
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
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(f.formGuid(), points, 500))
                        .toList(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "minus", Optional.empty())));
        reason(mesh, "minus", false, "MORPH_REFERENCED_FORM");
        reason(mesh, "plus", true, "INDEPENDENT_NON_DEFAULT_ORDINARY");
        var morphDefault = new ArtMeshAuthoringSnapshot(
                mesh.artMeshId(),
                mesh.sourceGuid(),
                mesh.sourceGeometry(),
                mesh.axes(),
                mesh.cells(),
                mesh.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "default", Optional.empty())));
        reason(morphDefault, "plus", false, "SHARED_OR_MORPH_DEFAULT_FORM");
    }

    @Test
    void warpProjectionUsesSameRulesAndClosedSchema() {
        var b = McpRotationRigDomainTest.snapshot(7);
        var controls = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        var warp = new WarpAuthoringSnapshot(
                new DeformerId("warp"),
                "source",
                1,
                1,
                false,
                b.axes().stream()
                        .map(a -> new WarpAuthoringSnapshot.Axis(
                                a.parameterGuid(),
                                a.parameterId(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()))
                        .toList(),
                b.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell(
                                c.formGuid(),
                                c.coordinates().stream()
                                        .map(v -> new WarpAuthoringSnapshot.Coordinate(v.parameterGuid(), v.value()))
                                        .toList()))
                        .toList(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), controls))
                        .toList());
        reason(warp, "default", false, "DEFAULT_FORM");
        reason(warp, "minus", true, "INDEPENDENT_NON_DEFAULT_ORDINARY");
        var rows = McpAuthoringProjection.warp(warp);
        assertEquals(
                3,
                rows.stream()
                        .filter(r -> "ordinary_write_eligibility".equals(r.get("kind")))
                        .count());
        rows.forEach(r -> assertTrue(McpJsonSchema.validates(r, McpAuthoringProjection.rowSchema()), r.toString()));
    }

    @Test
    void allRotationEligibilityMatchesUnchangedWriterPreflightAcrossSharedLayouts() {
        var b = McpRotationRigDomainTest.snapshot(7);
        for (int first = 0; first < 3; first++)
            for (int second = 0; second < 3; second++)
                for (int third = 0; third < 3; third++) {
                    int[] mapping = {first, second, third};
                    var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
                    for (int i = 0; i < 3; i++)
                        cells.add(new RotationAuthoringSnapshot.Cell(
                                b.forms().get(mapping[i]).formGuid(),
                                b.cells().get(i).coordinates()));
                    var snapshot = rotation(b.axes(), cells, b.forms());
                    var rows = McpKeyformWritePlan.eligibility(snapshot);
                    for (var cell : cells) {
                        var row = rows.stream()
                                .filter(r -> cell.formGuid().equals(r.get("formGuid")))
                                .findFirst()
                                .orElseThrow();
                        boolean accepted;
                        try {
                            McpKeyformWritePlan.prepare(
                                    snapshot,
                                    List.of(edit(cell, b.forms().get(0).value())));
                            accepted = true;
                        } catch (IllegalArgumentException expected) {
                            accepted = false;
                        }
                        assertEquals(accepted, row.get("snapshotEligible"), snapshot.toString());
                    }
                }
    }

    private static Map<String, Object> edit(RotationAuthoringSnapshot.Cell cell, RotationDeformerForm f) {
        return Map.of(
                "formGuid",
                cell.formGuid(),
                "coordinates",
                cell.coordinates().stream()
                        .map(c -> Map.of("parameterGuid", c.parameterGuid(), "value", c.value()))
                        .toList(),
                "form",
                Map.of(
                        "angle",
                        f.angle(),
                        "originX",
                        f.originX(),
                        "originY",
                        f.originY(),
                        "scale",
                        f.scale(),
                        "reflectedX",
                        f.reflectedX(),
                        "reflectedY",
                        f.reflectedY()));
    }

    private static RotationAuthoringSnapshot rotation(
            List<RotationAuthoringSnapshot.Axis> axes,
            List<RotationAuthoringSnapshot.Cell> cells,
            List<RotationAuthoringSnapshot.Form> forms) {
        var b = McpRotationRigDomainTest.snapshot(7);
        return new RotationAuthoringSnapshot(b.deformerId(), b.sourceGuid(), b.baseAngle(), axes, cells, forms);
    }

    private static void reason(Object snapshot, String guid, boolean eligible, String reason) {
        var row = McpKeyformWritePlan.eligibility(snapshot).stream()
                .filter(r -> guid.equals(r.get("formGuid")))
                .findFirst()
                .orElseThrow();
        assertEquals(eligible, row.get("snapshotEligible"));
        assertEquals(reason, row.get("reason"));
        assertEquals("UNPROVEN", row.get("hostWriteAdmission"));
        assertTrue(McpJsonSchema.validates(row, McpAuthoringProjection.rowSchema()));
        assertThrows(UnsupportedOperationException.class, () -> row.put("snapshotEligible", true));
    }
}
