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
