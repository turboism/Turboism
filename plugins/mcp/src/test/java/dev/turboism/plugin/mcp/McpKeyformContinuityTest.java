package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpKeyformContinuityTest {
    static final McpKeyformContinuity.Thresholds ZERO = new McpKeyformContinuity.Thresholds(0, 0, 0);

    static WarpAuthoringSnapshot warp(List<Float> keys, List<Float> shifts) {
        var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
        var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
        for (int i = 0; i < keys.size(); i++) {
            float x = shifts.get(i);
            String guid = "f" + i;
            forms.add(new WarpAuthoringSnapshot.Form(
                    guid, List.of(new Point2(x, 0), new Point2(x + 1, 0), new Point2(x, 1), new Point2(x + 1, 1))));
            cells.add(new WarpAuthoringSnapshot.Cell(
                    guid, List.of(new WarpAuthoringSnapshot.Coordinate("axis", keys.get(i)))));
        }
        return new WarpAuthoringSnapshot(
                new DeformerId("object"),
                "source",
                1,
                1,
                false,
                List.of(new WarpAuthoringSnapshot.Axis("axis", new ParameterId("AngleX"), -1, 0, 1, keys)),
                cells,
                forms);
    }

    static WarpAuthoringSnapshot warp() {
        return warp(List.of(-1f, 0f, 1f), List.of(-1f, 0f, 1f));
    }

    static RotationAuthoringSnapshot rotation() {
        var w = warp();
        return new RotationAuthoringSnapshot(
                w.deformerId(),
                w.sourceGuid(),
                77,
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
                                        .map(v ->
                                                new RotationAuthoringSnapshot.Coordinate(v.parameterGuid(), v.value()))
                                        .toList()))
                        .toList(),
                List.of(
                        new RotationAuthoringSnapshot.Form("f0", new RotationDeformerForm(350, 0, 0, 1, false, false)),
                        new RotationAuthoringSnapshot.Form("f1", new RotationDeformerForm(0, 0, 0, 1, false, false)),
                        new RotationAuthoringSnapshot.Form("f2", new RotationDeformerForm(10, 1, 0, 2, true, true))));
    }

    @Test
    void constantVelocityOnUnevenKeysHasNoKinkForMeshAndWarp() {
        var w = warp(List.of(-1f, -.5f, 1f), List.of(-2f, -1f, 2f));
        for (Object s : List.of(w, McpMeshRigPlanTest.fromWarp(w))) {
            var r = McpKeyformContinuity.analyze(s, ZERO, 200);
            assertEquals(0, r.findingCount());
            assertEquals(1, r.triples());
            assertEquals(4, r.pointComparisons());
            assertEquals(0, r.maximumPositionSlopeChange());
        }
    }

    @Test
    void actualIntervalsAndFullParameterRangeDetermineSlopeUnits() {
        var w = warp(List.of(-1f, -.5f, 1f), List.of(0f, 1f, 1f));
        var r = McpKeyformContinuity.analyze(w, ZERO, 200);
        assertEquals(4, r.findingCount());
        assertEquals(4, r.maximumPositionSlopeChange());
        var f = r.findings().get(0);
        assertEquals(List.of(-1f, -.5f, 1f), f.get("axisValues"));
        assertEquals(List.of("f0", "f1", "f2"), f.get("formGuids"));
        assertEquals(List.of(Map.of("parameterGuid", "axis", "value", -.5f)), f.get("centerCoordinates"));
        assertEquals(0, f.get("pointIndex"));
        assertEquals(
                0,
                McpKeyformContinuity.analyze(w, new McpKeyformContinuity.Thresholds(4, 0, 0), 200)
                        .findingCount());
    }

    @Test
    void allAxisLinesAreCheckedByGuidDespiteDuplicateDisplayIdsAndOrder() {
        var base = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        // All grid forms are identical: every checked line has zero slope, including shared default coordinates.
        var same = base.forms().get(0).controlPoints();
        var s = new WarpAuthoringSnapshot(
                base.deformerId(),
                base.sourceGuid(),
                base.rows(),
                base.columns(),
                base.quadTransform(),
                base.axes().stream()
                        .map(a -> new WarpAuthoringSnapshot.Axis(
                                a.parameterGuid(),
                                new ParameterId("same"),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()))
                        .toList(),
                base.cells(),
                base.forms().stream()
                        .filter(f -> !f.formGuid().equals("unused"))
                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), same))
                        .toList());
        var r = McpKeyformContinuity.analyze(s, ZERO, 200);
        assertEquals(27, r.triples());
        assertEquals(27L * same.size(), r.pointComparisons());
        assertEquals(0, r.findingCount());
    }

    @Test
    void cornerChangeIsLocatedOnEachAffectedAxisLineWithoutDroppingOtherCoordinates() {
        var b = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        var base = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        var modified = List.of(new Point2(1, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        var s = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                1,
                false,
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .filter(f -> !f.formGuid().equals("unused"))
                        .map(f -> new WarpAuthoringSnapshot.Form(
                                f.formGuid(), f.formGuid().equals("f0") ? modified : base))
                        .toList());
        var r = McpKeyformContinuity.analyze(s, ZERO, 200);
        assertEquals(3, r.findingCount());
        assertEquals(27, r.triples());
        assertEquals(
                Set.of("g0", "g1", "g2"),
                new HashSet<>(
                        r.findings().stream().map(f -> f.get("parameterGuid")).toList()));
        assertTrue(r.findings().stream()
                .allMatch(f -> ((List<?>) f.get("centerCoordinates")).size() == 3
                        && f.get("pointIndex").equals(0)));
    }

    @Test
    void rotationReportsRawAngleOriginScaleAndReflectionsWithoutWrapping() {
        var r = McpKeyformContinuity.analyze(rotation(), ZERO, 200);
        assertEquals(5, r.findingCount());
        assertEquals(720, r.maximumAngleSlopeChange());
        assertEquals(2, r.maximumPositionSlopeChange());
        assertEquals(2, r.maximumScaleSlopeChange());
        assertEquals(
                Set.of("origin", "angle", "scale", "reflectedX", "reflectedY"),
                new HashSet<>(r.findings().stream().map(f -> f.get("component")).toList()));
        assertTrue(r.findings().stream().allMatch(f -> f.get("pointIndex") == null));
    }

    @Test
    void retainedFindingsDoNotLimitAnalysisOrStatistics() {
        var w = warp(List.of(-1f, 0f, 1f), List.of(0f, 0f, 2f));
        var r = McpKeyformContinuity.analyze(w, ZERO, 1);
        assertEquals(4, r.findingCount());
        assertEquals(1, r.findings().size());
        assertEquals(4, r.pointComparisons());
    }

    @Test
    void sharedOrdinaryFormsAreReadableAndDefaultIsNotAssumedFirst() {
        var w = warp();
        var s = new WarpAuthoringSnapshot(
                w.deformerId(),
                w.sourceGuid(),
                1,
                1,
                false,
                w.axes(),
                w.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell("f1", c.coordinates()))
                        .toList(),
                List.of(w.forms().get(1)));
        assertEquals(0, McpKeyformContinuity.analyze(s, ZERO, 200).findingCount());
    }

    @Test
    void incompleteDuplicateTuplesAndOrphanFormsNeverReturnAReport() {
        var w = warp();
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpKeyformContinuity.analyze(
                        new WarpAuthoringSnapshot(
                                w.deformerId(),
                                w.sourceGuid(),
                                1,
                                1,
                                false,
                                w.axes(),
                                w.cells().subList(0, 2),
                                w.forms()),
                        ZERO,
                        200));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformContinuity.analyze(
                        new WarpAuthoringSnapshot(
                                w.deformerId(),
                                w.sourceGuid(),
                                1,
                                1,
                                false,
                                w.axes(),
                                List.of(
                                        w.cells().get(0),
                                        w.cells().get(0),
                                        w.cells().get(2)),
                                w.forms()),
                        ZERO,
                        200));
        var forms = new ArrayList<>(w.forms());
        forms.add(new WarpAuthoringSnapshot.Form("orphan", w.forms().get(0).controlPoints()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpKeyformContinuity.analyze(
                        new WarpAuthoringSnapshot(
                                w.deformerId(), w.sourceGuid(), 1, 1, false, w.axes(), w.cells(), forms),
                        ZERO,
                        200));
    }

    @Test
    void unsupportedAxesMorphAndBudgetsAreExplicit() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpKeyformContinuity.analyze(warp(List.of(-1f, 1f), List.of(0f, 0f)), ZERO, 200));
        var mesh = McpMeshRigPlanTest.fromWarp(warp());
        var morph = new ArtMeshAuthoringSnapshot(
                mesh.artMeshId(),
                mesh.sourceGuid(),
                mesh.sourceGeometry(),
                mesh.axes(),
                mesh.cells(),
                mesh.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "f1", Optional.of(1f))));
        assertThrows(UnsupportedOperationException.class, () -> McpKeyformContinuity.analyze(morph, ZERO, 200));
        var w = warp();
        var big = Collections.nCopies(5000, new Point2(0, 0));
        assertThrows(
                McpBindingGridSnapshot.Budget.class,
                () -> McpKeyformContinuity.analyze(
                        new WarpAuthoringSnapshot(
                                w.deformerId(),
                                w.sourceGuid(),
                                1,
                                2499,
                                false,
                                w.axes(),
                                w.cells(),
                                w.forms().stream()
                                        .map(f -> new WarpAuthoringSnapshot.Form(f.formGuid(), big))
                                        .toList()),
                        ZERO,
                        200));
        assertThrows(IllegalArgumentException.class, () -> new McpKeyformContinuity.Thresholds(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new McpKeyformContinuity.Thresholds(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> McpKeyformContinuity.analyze(w, ZERO, 0));
    }

    @Test
    void floatExtremesRemainFiniteWithoutSubtractingNormalizedAbsoluteKeys() {
        var w = warp(List.of(-1f, 0f, 1f), List.of(-Float.MAX_VALUE, 0f, Float.MAX_VALUE));
        var r = McpKeyformContinuity.analyze(w, ZERO, 200);
        assertTrue(Double.isFinite(r.maximumPositionSlopeChange()));
        assertEquals(0, r.findingCount());
    }
}
