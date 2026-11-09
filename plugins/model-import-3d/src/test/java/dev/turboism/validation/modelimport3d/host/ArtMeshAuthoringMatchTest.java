package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArtMeshAuthoringMatchTest {

    @Test
    void acceptsReadbackWithinTheStoragePrecision() {
        final String result =
                ArtMeshAuthoringMatch.describe(snapshot(1.0f, 2.0f), snapshot(1.0f + 6e-6f, 2.0f - 6e-6f));
        assertNull(result, "a few units of least precision must not fail a correct write");
    }

    @Test
    void rejectsARealPositionDriftAndNamesTheWorstDelta() {
        final String result = ArtMeshAuthoringMatch.describe(snapshot(1.0f, 2.0f), snapshot(1.25f, 2.0f));
        assertTrue(result != null && result.contains("delta"), "a real drift must be reported: " + result);
    }

    @Test
    void rejectsADifferentFormSetAndADifferentDrawOrder() {
        assertFalse(
                ArtMeshAuthoringMatch.matches(snapshot(1.0f, 2.0f), snapshotWith("form-b", 1.0f, 2.0f, 0)),
                "a different form guid must fail");
        assertFalse(
                ArtMeshAuthoringMatch.matches(snapshot(1.0f, 2.0f), snapshotWith("form-a", 1.0f, 2.0f, 7)),
                "a different draw order must fail");
    }

    /**
     * A compare-and-set batch owns only the forms it lists. The neutral form keeps the draw order it
     * was created with, so predicting that order turned a correct sphere import into a rollback.
     */
    @Test
    void editCheckIgnoresFormsTheBatchNeverTouched() {
        final var readback = snapshotWith("form-a", 1.0f, 2.0f, 7);
        final var positions = new dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions(
                List.of(new ArtMeshAuthoringSnapshot.Coordinate("guid-yaw", -30.0f)),
                "form-a",
                List.of(new Point2(1.0f, 2.0f), new Point2(2.0f, 2.0f), new Point2(2.0f, 3.0f)));
        assertNull(
                ArtMeshAuthoringMatch.describeEdits(List.of(positions), List.of(), readback),
                "a form the batch left alone must not be judged against a predicted order");
    }

    @Test
    void editCheckNamesTheFieldBeforeTheFormGuid() {
        final var readback = snapshotWith("form-a", 1.0f, 2.0f, 3);
        final var order = new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                List.of(new ArtMeshAuthoringSnapshot.Coordinate("guid-yaw", -30.0f)), "form-a", 9);
        final String result = ArtMeshAuthoringMatch.describeEdits(List.of(), List.of(order), readback);
        assertTrue(
                result != null && result.startsWith("draw order"),
                "the authoring diagnostic is cut at 128 characters, so the field must lead: " + result);

        final var drifted = new dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions(
                List.of(new ArtMeshAuthoringSnapshot.Coordinate("guid-yaw", -30.0f)),
                "form-a",
                List.of(new Point2(1.5f, 2.0f), new Point2(2.0f, 2.0f), new Point2(2.0f, 3.0f)));
        final String positions = ArtMeshAuthoringMatch.describeEdits(List.of(drifted), List.of(), readback);
        assertTrue(
                positions != null && positions.startsWith("positions delta"),
                "a real position drift must lead with the field: " + positions);
    }

    private static ArtMeshAuthoringSnapshot snapshot(final float x, final float y) {
        return snapshotWith("form-a", x, y, 0);
    }

    private static ArtMeshAuthoringSnapshot snapshotWith(
            final String formGuid, final float x, final float y, final int drawOrder) {
        final var geometry = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f)),
                List.of(0, 1, 2));
        final var axis = new ArtMeshAuthoringSnapshot.Axis(
                "guid-yaw",
                new dev.turboism.sdk.cubism.id.ParameterId("ParamAngleX"),
                -30.0f,
                0.0f,
                30.0f,
                List.of(-30.0f, 0.0f, 30.0f));
        final var cell = new ArtMeshAuthoringSnapshot.Cell(
                formGuid, List.of(new ArtMeshAuthoringSnapshot.Coordinate("guid-yaw", 0.0f)));
        final var form = new ArtMeshAuthoringSnapshot.Form(
                formGuid,
                List.of(new Point2(x, y), new Point2(x + 1.0f, y), new Point2(x + 1.0f, y + 1.0f)),
                drawOrder,
                Optional.empty());
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId("ArtMesh1"),
                "source-guid",
                geometry,
                List.of(axis),
                List.of(cell),
                List.of(form),
                List.of());
    }
}
