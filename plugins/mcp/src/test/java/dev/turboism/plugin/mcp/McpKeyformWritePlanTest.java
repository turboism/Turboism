package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpKeyformWritePlanTest {
    private static final List<Point2> POINTS = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1));
    private static final ArtMeshGeometry GEOMETRY = new ArtMeshGeometry(POINTS, POINTS, List.of(0, 1, 2));
    private static final RotationDeformerForm NEUTRAL = new RotationDeformerForm(0, 0, 0, 1, false, false);
    private static final List<Map<String, Object>> COORDINATES =
            List.of(Map.of("parameterGuid", "axis-guid", "value", 1));

    @Test
    void positionAndOrderPlansPreserveEveryStoredOpacityObservation() {
        var b = mesh();
        var before = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                f.formGuid(), f.positions(), f.drawOrder(), Optional.of(f.drawOrder() / 200F)))
                        .toList(),
                b.morphKeys());
        for (var edits : List.of(List.of(edit("drawOrder", 750)), List.of(edit("positions", pointJson())))) {
            var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, edits);
            assertEquals(
                    before.forms().stream()
                            .map(ArtMeshAuthoringSnapshot.Form::opacity)
                            .toList(),
                    plan.after().forms().stream()
                            .map(ArtMeshAuthoringSnapshot.Form::opacity)
                            .toList());
            assertEquals(before.forms().get(0), plan.after().forms().get(0));
        }
        var opacity = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(edit("opacity", .5)));
        assertEquals(Optional.of(.5F), opacity.after().forms().get(1).opacity());
    }

    @Test
    void opacityPlansAcceptAllSevenFieldCombinationsAndPreserveOmittedValues() {
        var b = mesh();
        var before = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                f.formGuid(), f.positions(), f.drawOrder(), Optional.of(.75F)))
                        .toList(),
                b.morphKeys());
        for (int mask = 1; mask < 8; mask++) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("formGuid", "target");
            fields.put("coordinates", COORDINATES);
            if ((mask & 1) != 0) fields.put("positions", pointJson());
            if ((mask & 2) != 0) fields.put("drawOrder", 750);
            if ((mask & 4) != 0) fields.put("opacity", .25);
            var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(fields));
            assertEquals(
                    Optional.of((mask & 4) != 0 ? .25F : .75F),
                    plan.after().forms().get(1).opacity());
            assertEquals(
                    (mask & 2) != 0 ? 750 : before.forms().get(1).drawOrder(),
                    plan.after().forms().get(1).drawOrder());
            assertEquals((mask & 4) != 0 ? 1 : 0, plan.values().size());
            assertEquals(before.forms().get(0), plan.after().forms().get(0));
            assertEquals(before.sourceGeometry(), plan.after().sourceGeometry());
        }
        for (Object value : List.of(
                -.001,
                1.00000001,
                new BigDecimal("-1e-999"),
                new BigDecimal("1.000000000000000000001"),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                "0.5"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpKeyformWritePlan.prepare(before, List.of(edit("opacity", value))));
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpKeyformWritePlan.prepare(mesh(), List.of(edit("opacity", .5))));
        var withUnknownField = new HashMap<>(edit("opacity", .5));
        withUnknownField.put("color", "red");
        assertThrows(
                IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(before, List.of(withUnknownField)));
    }

    @Test
    void drawOrderOnlyKeepsAllPositionsAndUsesBoundedExactInteger() {
        var before = mesh();
        for (int order : List.of(0, 1000)) {
            var plan =
                    (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(edit("drawOrder", order)));
            assertTrue(plan.edits().isEmpty());
            assertEquals(order, plan.drawOrders().get(0).drawOrder());
            assertEquals(order, plan.after().forms().get(1).drawOrder());
            assertEquals(
                    before.forms().get(1).positions(),
                    plan.after().forms().get(1).positions());
            assertEquals(before.forms().get(0), plan.after().forms().get(0));
            assertEquals(before.sourceGeometry(), plan.after().sourceGeometry());
        }
        for (Object order : List.of(-1, 1001, .5, Double.NaN, Double.POSITIVE_INFINITY, "500", 1e30))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpKeyformWritePlan.prepare(before, List.of(edit("drawOrder", order))));
    }

    @Test
    void combinedPositionAndDrawOrderBuildOneExpectedState() {
        var edit = new HashMap<>(edit("positions", pointJson()));
        edit.put("drawOrder", 750);
        var before = mesh();
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(edit));
        assertEquals(plan.edits().get(0).coordinates(), plan.drawOrders().get(0).coordinates());
        assertEquals(plan.edits().get(0).formGuid(), plan.drawOrders().get(0).formGuid());
        assertEquals(750, plan.after().forms().get(1).drawOrder());
        assertEquals(before.sourceGeometry(), plan.after().sourceGeometry());
    }

    @Test
    void mixedEditsPadOnlyExpectedFieldsToSameCombinedTargetSets() {
        var b = mesh();
        var before = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        b.cells().get(0),
                        b.cells().get(1),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "other", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                List.of(b.forms().get(0), b.forms().get(1), new ArtMeshAuthoringSnapshot.Form("other", POINTS, 200)),
                List.of());
        var position =
                edit("positions", List.of(Map.of("x", .25, "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1)));
        var order = Map.of(
                "formGuid",
                "other",
                "coordinates",
                List.of(Map.of("parameterGuid", "axis-guid", "value", 2)),
                "drawOrder",
                700);
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(position, order));
        assertEquals(2, plan.edits().size());
        assertEquals(2, plan.drawOrders().size());
        assertEquals(b.forms().get(1).drawOrder(), plan.drawOrders().get(0).drawOrder());
        assertEquals(POINTS, plan.edits().get(1).positions());
        assertEquals(
                new Point2(.25F, 0), plan.after().forms().get(1).positions().get(0));
        assertEquals(700, plan.after().forms().get(2).drawOrder());
    }

    @Test
    void combinedPaddingCountsRetainedVerticesAgainstBudget() {
        var points = Collections.nCopies(8193, new Point2(0, 0));
        var before = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source",
                new ArtMeshGeometry(points, points, List.of()),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Cell(
                                "neutral", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 1))),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "other", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", points, 100),
                        new ArtMeshAuthoringSnapshot.Form("target", points, 101),
                        new ArtMeshAuthoringSnapshot.Form("other", points, 102)),
                List.of());
        var order = Map.of(
                "formGuid",
                "other",
                "coordinates",
                List.of(Map.of("parameterGuid", "axis-guid", "value", 2)),
                "drawOrder",
                700);
        var positions = edit("positions", Collections.nCopies(8193, Map.of("x", 0, "y", 0)));
        assertThrows(
                IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(before, List.of(positions, order)));
        assertTrue(((McpKeyformWritePlan.Mesh)
                        McpKeyformWritePlan.prepare(before, List.of(edit("drawOrder", 600), order)))
                .edits()
                .isEmpty());
        var shared = new ArtMeshAuthoringSnapshot(
                before.artMeshId(),
                before.sourceGuid(),
                before.sourceGeometry(),
                before.axes(),
                List.of(
                        before.cells().get(0),
                        before.cells().get(1),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                List.of(before.forms().get(0), before.forms().get(1)),
                List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(shared, List.of(edit("drawOrder", 600))));
    }

    @Test
    void drawOrderCannotBypassDefaultSharedMorphAndClosedFields() {
        var b = mesh();
        var defaultEdit = Map.of(
                "formGuid",
                "neutral",
                "coordinates",
                List.of(Map.of("parameterGuid", "axis-guid", "value", 0)),
                "drawOrder",
                600);
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(b, List.of(defaultEdit)));
        var morph = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "target", Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(morph, List.of(edit("drawOrder", 600))));
        var extra = new HashMap<>(edit("drawOrder", 600));
        extra.put("color", "red");
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(b, List.of(extra)));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(warp(), List.of(edit("drawOrder", 600))));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit("drawOrder", 600))));
    }

    @Test
    void meshRebuildsOnlyRequestedStoredPositionsAndKeepsSourceTopologyOrdersAndMorph() {
        var before = mesh();
        var replacement = List.of(Map.of("x", 0.25, "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1));
        var plan =
                (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(before, List.of(edit("positions", replacement)));
        assertSame(before, plan.before());
        assertEquals(new Point2(.25F, 0), plan.edits().get(0).positions().get(0));
        assertEquals(
                new Point2(.25F, 0), plan.after().forms().get(1).positions().get(0));
        assertEquals(before.sourceGeometry(), plan.after().sourceGeometry());
        assertEquals(before.axes(), plan.after().axes());
        assertEquals(before.cells(), plan.after().cells());
        assertEquals(before.forms().get(0), plan.after().forms().get(0));
        assertEquals(
                before.forms().get(1).drawOrder(), plan.after().forms().get(1).drawOrder());
        assertEquals(before.morphKeys(), plan.after().morphKeys());
        assertEquals(POINTS, before.forms().get(1).positions());
        assertThrows(UnsupportedOperationException.class, () -> plan.edits().clear());
    }

    @Test
    void warpRebuildsNativeGridWithoutChangingRowsColumnsOrNeutralForm() {
        var before = warp();
        var points = List.of(
                Map.of("x", .125, "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1), Map.of("x", 1, "y", 1));
        var plan =
                (McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(before, List.of(edit("controlPoints", points)));
        assertEquals(1, plan.after().rows());
        assertEquals(1, plan.after().columns());
        assertEquals(before.quadTransform(), plan.after().quadTransform());
        assertEquals(before.forms().get(0), plan.after().forms().get(0));
        assertEquals(
                new Point2(.125F, 0),
                plan.after().forms().get(1).controlPoints().get(0));
        assertEquals(4, plan.edits().get(0).controlPoints().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(before, List.of(edit("controlPoints", pointJson()))));
    }

    @Test
    void rotationWritesWholeStoredFormAndKeepsBaseAngleAndNeutral() {
        var before = rotation();
        var form = form();
        var plan = (McpKeyformWritePlan.Rotation) McpKeyformWritePlan.prepare(before, List.of(edit("form", form)));
        assertEquals(20, plan.after().baseAngle());
        assertEquals(before.forms().get(0), plan.after().forms().get(0));
        assertEquals(
                new RotationDeformerForm(12, 2, 3, .75F, true, false),
                plan.edits().get(0).value());
        assertEquals(plan.edits().get(0).value(), plan.after().forms().get(1).value());
    }

    @Test
    void rejectsDefaultSharedMorphAndDuplicateFormTargets() {
        var defaultEdit = new HashMap<>(edit("positions", pointJson()));
        defaultEdit.put("formGuid", "neutral");
        defaultEdit.put("coordinates", List.of(Map.of("parameterGuid", "axis-guid", "value", 0)));
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(mesh(), List.of(defaultEdit)));
        var b = mesh();
        var shared = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        b.cells().get(0),
                        b.cells().get(1),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                b.forms(),
                List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(shared, List.of(edit("positions", pointJson()))));
        var morph = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "target", Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(morph, List.of(edit("positions", pointJson()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(
                        b, List.of(edit("positions", pointJson()), edit("positions", pointJson()))));
    }

    @Test
    void requiresExactDeclaredDefaultAndRejectsAmbiguousGrid() {
        var b = rotation();
        var noDefault = new RotationAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.baseAngle(),
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, .5F, 1, List.of(0F, 1F))),
                b.cells(),
                b.forms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(noDefault, List.of(edit("form", form()))));
        var duplicateCell = new RotationAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.baseAngle(),
                b.axes(),
                List.of(b.cells().get(0), b.cells().get(1), b.cells().get(1)),
                b.forms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(duplicateCell, List.of(edit("form", form()))));
        var duplicateKeys = new RotationAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.baseAngle(),
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F, 1F))),
                b.cells(),
                b.forms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(duplicateKeys, List.of(edit("form", form()))));
    }

    @Test
    void exactGuidTupleWorksWithDuplicateDisplayIdsAndReorderedCoordinates() {
        var b = rotation();
        var axes = List.of(
                new RotationAuthoringSnapshot.Axis("x-guid", new ParameterId("duplicate"), 0, 0, 1, List.of(0F, 1F)),
                new RotationAuthoringSnapshot.Axis("y-guid", new ParameterId("duplicate"), 0, 0, 1, List.of(0F, 1F)));
        var snapshot = new RotationAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.baseAngle(),
                axes,
                List.of(
                        new RotationAuthoringSnapshot.Cell(
                                "neutral",
                                List.of(
                                        new RotationAuthoringSnapshot.Coordinate("x-guid", 0),
                                        new RotationAuthoringSnapshot.Coordinate("y-guid", 0))),
                        new RotationAuthoringSnapshot.Cell(
                                "target",
                                List.of(
                                        new RotationAuthoringSnapshot.Coordinate("x-guid", 1),
                                        new RotationAuthoringSnapshot.Coordinate("y-guid", 1)))),
                b.forms());
        var edit = new HashMap<>(edit("form", form()));
        edit.put(
                "coordinates",
                List.of(Map.of("parameterGuid", "y-guid", "value", 1), Map.of("parameterGuid", "x-guid", "value", 1)));
        assertInstanceOf(McpKeyformWritePlan.Rotation.class, McpKeyformWritePlan.prepare(snapshot, List.of(edit)));
        edit.put("coordinates", List.of(Map.of("parameterGuid", "x-guid", "value", 1)));
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(snapshot, List.of(edit)));
    }

    @Test
    void rejectsUnknownDuplicateOrRoundedCoordinatesAndWrongFormIdentity() {
        for (var coordinates : List.of(
                List.of(Map.of("parameterGuid", "display", "value", 1)),
                List.of(
                        Map.of("parameterGuid", "axis-guid", "value", 1),
                        Map.of("parameterGuid", "axis-guid", "value", 1)),
                List.of(Map.of("parameterGuid", "axis-guid", "value", new BigDecimal("1.000000001"))),
                List.of(Map.of("parameterGuid", "axis-guid", "value", "1")))) {
            var edit = new HashMap<>(edit("form", form()));
            edit.put("coordinates", coordinates);
            assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit)));
        }
        var edit = new HashMap<>(edit("form", form()));
        edit.put("formGuid", "unknown");
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit)));
    }

    @Test
    void rejectsCrossTypeUnknownFieldsMissingRotationFieldsAndInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(mesh(), List.of(edit("form", form()))));
        var extra = new HashMap<>(edit("positions", pointJson()));
        extra.put("color", "red");
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(mesh(), List.of(extra)));
        var missing = new HashMap<>(form());
        missing.remove("reflectedY");
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit("form", missing))));
        for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, "12", true)) {
            var bad = new HashMap<>(form());
            bad.put("angle", invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit("form", bad))));
        }
        var zeroScale = new HashMap<>(form());
        zeroScale.put("scale", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(rotation(), List.of(edit("form", zeroScale))));
        var invalidPoint = List.of(Map.of("x", Double.NaN, "y", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(mesh(), List.of(edit("positions", invalidPoint))));
    }

    @Test
    void enforcesBatchAndAggregatePointBudgetsAndDoesNotRetainMutableInputs() {
        assertThrows(IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(mesh(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(mesh(), Collections.nCopies(65, edit("positions", pointJson()))));
        var excessive = Collections.nCopies(McpKeyformWritePlan.MAX_POINTS + 1, new Point2(0, 0));
        var b = mesh();
        var large = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                new ArtMeshGeometry(excessive, excessive, List.of()),
                b.axes(),
                b.cells(),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", excessive, 0),
                        new ArtMeshAuthoringSnapshot.Form("target", excessive, 1)),
                List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(
                        large,
                        List.of(edit("positions", Collections.nCopies(excessive.size(), Map.of("x", 0, "y", 0))))));
        var mutablePoints = new ArrayList<>(pointJson());
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(b, List.of(edit("positions", mutablePoints)));
        mutablePoints.clear();
        assertEquals(3, plan.edits().get(0).positions().size());
    }

    @Test
    void rejectsSharedAndDefaultTargetsForWarpAndRotationAsWell() {
        var w = warp();
        var r = rotation();
        var points = Collections.nCopies(4, Map.of("x", 0, "y", 0));
        for (Object snapshot : List.of(w, r)) {
            String field = snapshot instanceof WarpAuthoringSnapshot ? "controlPoints" : "form";
            Object value = snapshot instanceof WarpAuthoringSnapshot ? points : form();
            var defaultEdit = new HashMap<>(edit(field, value));
            defaultEdit.put("formGuid", "neutral");
            defaultEdit.put("coordinates", List.of(Map.of("parameterGuid", "axis-guid", "value", 0)));
            assertThrows(
                    IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(snapshot, List.of(defaultEdit)));
        }
        var sharedWarp = new WarpAuthoringSnapshot(
                w.deformerId(),
                w.sourceGuid(),
                w.rows(),
                w.columns(),
                w.quadTransform(),
                List.of(new WarpAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        w.cells().get(0),
                        w.cells().get(1),
                        new WarpAuthoringSnapshot.Cell(
                                "target", List.of(new WarpAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                w.forms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(sharedWarp, List.of(edit("controlPoints", points))));
        var sharedRotation = new RotationAuthoringSnapshot(
                r.deformerId(),
                r.sourceGuid(),
                r.baseAngle(),
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        r.cells().get(0),
                        r.cells().get(1),
                        new RotationAuthoringSnapshot.Cell(
                                "target", List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                r.forms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpKeyformWritePlan.prepare(sharedRotation, List.of(edit("form", form()))));
    }

    @Test
    void aggregatePointLimitAppliesEvenWhenEachFormFitsAndGeometryAllowsNativeFloatRounding() {
        var b = mesh();
        int count = McpKeyformWritePlan.MAX_POINTS / 2 + 1;
        var points = Collections.nCopies(count, new Point2(0, 0));
        var snapshot = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                new ArtMeshGeometry(points, points, List.of()),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        b.cells().get(0),
                        b.cells().get(1),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target-2", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", points, 0),
                        new ArtMeshAuthoringSnapshot.Form("target", points, 1),
                        new ArtMeshAuthoringSnapshot.Form("target-2", points, 2)),
                List.of());
        var encodedPoints = Collections.nCopies(count, Map.of("x", 0, "y", 0));
        var first = edit("positions", encodedPoints);
        var second = Map.of(
                "formGuid",
                "target-2",
                "coordinates",
                List.of(Map.of("parameterGuid", "axis-guid", "value", 2)),
                "positions",
                encodedPoints);
        assertInstanceOf(McpKeyformWritePlan.Mesh.class, McpKeyformWritePlan.prepare(snapshot, List.of(first)));
        assertThrows(
                IllegalArgumentException.class, () -> McpKeyformWritePlan.prepare(snapshot, List.of(first, second)));
        var rounded = List.of(
                Map.of("x", new BigDecimal("0.123456789"), "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1));
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(b, List.of(edit("positions", rounded)));
        assertEquals(
                0.123456789F, plan.after().forms().get(1).positions().get(0).x());
    }

    private static ArtMeshAuthoringSnapshot mesh() {
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source-mesh",
                GEOMETRY,
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Cell(
                                "neutral", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", POINTS, 100),
                        new ArtMeshAuthoringSnapshot.Form("target", POINTS, 101)),
                List.of());
    }

    private static WarpAuthoringSnapshot warp() {
        var points = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        return new WarpAuthoringSnapshot(
                new DeformerId("warp"),
                "source-warp",
                1,
                1,
                true,
                List.of(new WarpAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new WarpAuthoringSnapshot.Cell(
                                "neutral", List.of(new WarpAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new WarpAuthoringSnapshot.Cell(
                                "target", List.of(new WarpAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new WarpAuthoringSnapshot.Form("neutral", points),
                        new WarpAuthoringSnapshot.Form("target", points)));
    }

    private static RotationAuthoringSnapshot rotation() {
        return new RotationAuthoringSnapshot(
                new DeformerId("rotation"),
                "source-rotation",
                20,
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new RotationAuthoringSnapshot.Cell(
                                "neutral", List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new RotationAuthoringSnapshot.Cell(
                                "target", List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new RotationAuthoringSnapshot.Form("neutral", NEUTRAL),
                        new RotationAuthoringSnapshot.Form("target", NEUTRAL)));
    }

    private static Map<String, Object> edit(String field, Object value) {
        return Map.of("formGuid", "target", "coordinates", COORDINATES, field, value);
    }

    private static List<Map<String, Integer>> pointJson() {
        return List.of(Map.of("x", 0, "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1));
    }

    private static Map<String, Object> form() {
        return Map.of("angle", 12, "originX", 2, "originY", 3, "scale", .75, "reflectedX", true, "reflectedY", false);
    }
}
