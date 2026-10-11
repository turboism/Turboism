package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ArtMeshAuthoringSnapshotTest {
    private static final ArtMeshGeometry GEOMETRY = new ArtMeshGeometry(
        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1)),
        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1)), List.of(0, 1, 2));
    private static final ArtMeshAuthoringSnapshot.Form FORM =
        new ArtMeshAuthoringSnapshot.Form("f", GEOMETRY.positions(), 3);
    private static final ArtMeshAuthoringSnapshot.Axis AXIS =
        new ArtMeshAuthoringSnapshot.Axis("p", new ParameterId("id"), -1, .25F, 1, List.of(-1F, 0F, 1F));
    private static final ArtMeshAuthoringSnapshot.Cell CELL = new ArtMeshAuthoringSnapshot.Cell(
        "f", List.of(new ArtMeshAuthoringSnapshot.Coordinate("p", 0)));

    @Test
    void copiesAllCollectionBoundariesAndRetainsAliasesWithoutGuessingSemantics() {
        final var positions = new ArrayList<>(GEOMETRY.positions());
        final var form = new ArtMeshAuthoringSnapshot.Form("f", positions, 3);
        final var keys = new ArrayList<>(List.of(-1F, 0F, 1F));
        final var axis = new ArtMeshAuthoringSnapshot.Axis("p", new ParameterId("id"), -1, .25F, 1, keys);
        final var coordinates = new ArrayList<>(CELL.coordinates());
        final var cell = new ArtMeshAuthoringSnapshot.Cell("f", coordinates);
        final var axes = new ArrayList<>(List.of(axis));
        final var cells = new ArrayList<>(List.of(cell, cell));
        final var forms = new ArrayList<>(List.of(form));
        final var morphs = new ArrayList<>(List.of(
            new ArtMeshAuthoringSnapshot.MorphKey("m", "f", Optional.empty()),
            new ArtMeshAuthoringSnapshot.MorphKey("m", "f", Optional.of(0F))));
        final var snapshot = snapshot(axes, cells, forms, morphs);
        positions.clear(); keys.clear(); coordinates.clear(); axes.clear(); cells.clear(); forms.clear(); morphs.clear();
        assertEquals(3, snapshot.forms().get(0).positions().size());
        assertEquals(3, snapshot.axes().get(0).keys().size());
        assertEquals(.25F, snapshot.axes().get(0).defaultValue());
        assertEquals(2, snapshot.cells().size());
        assertEquals(1, snapshot.cells().get(0).coordinates().size());
        assertEquals(Optional.empty(), snapshot.morphKeys().get(0).keyValue());
        assertEquals(Optional.of(0F), snapshot.morphKeys().get(1).keyValue());
        for (final List<?> values : List.of(snapshot.axes(), snapshot.cells(), snapshot.forms(),
            snapshot.morphKeys(), snapshot.forms().get(0).positions(), snapshot.axes().get(0).keys(),
            snapshot.cells().get(0).coordinates())) {
            assertThrows(UnsupportedOperationException.class, values::clear);
        }
    }

    @Test
    void rejectsIncompleteDanglingDuplicateOrInvalidData() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS, AXIS), List.of(CELL), List.of(FORM), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS), List.of(CELL), List.of(FORM, FORM), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS), List.of(CELL), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS),
            List.of(new ArtMeshAuthoringSnapshot.Cell("f", List.of())), List.of(FORM), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS), List.of(
            new ArtMeshAuthoringSnapshot.Cell("f", List.of(CELL.coordinates().get(0), CELL.coordinates().get(0)))), List.of(FORM), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS), List.of(),
            List.of(new ArtMeshAuthoringSnapshot.Form("f", List.of(), 0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(AXIS), List.of(), List.of(FORM),
            List.of(new ArtMeshAuthoringSnapshot.MorphKey("m", "missing", Optional.of(1F)))));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshAuthoringSnapshot.Coordinate("p", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshAuthoringSnapshot.MorphKey("m", "f", Optional.of(Float.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshAuthoringSnapshot.Axis("p", new ParameterId("id"), 1, 0, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshAuthoringSnapshot.Form("", List.of(), 0));
        assertThrows(NullPointerException.class, () -> new ArtMeshAuthoringSnapshot.MorphKey("m", "f", null));
    }

    @Test
    void methodIsAdditiveDefaultAndExactlyVersioned() throws Exception {
        final var method = Drawable.class.getMethod("authoringSnapshot");
        assertTrue(method.isDefault());
        assertEquals(ArtMeshAuthoringSnapshot.class, method.getReturnType());
        assertArrayEquals(new String[]{"5.2.03", "5.3.02", "5.3.03"}, method.getAnnotation(dev.turboism.sdk.CubismEditor.class).value());
        assertEquals(0, method.getParameterCount());
    }

    private static ArtMeshAuthoringSnapshot snapshot(
        final List<ArtMeshAuthoringSnapshot.Axis> axes, final List<ArtMeshAuthoringSnapshot.Cell> cells,
        final List<ArtMeshAuthoringSnapshot.Form> forms, final List<ArtMeshAuthoringSnapshot.MorphKey> morphs
    ) {
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("mesh"), "source", GEOMETRY, axes, cells, forms, morphs);
    }
}
