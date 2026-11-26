package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtMeshMirrorAxisTest {
    private static ArtMeshMirrorAxis.Point p(float x, float y) {
        return new ArtMeshMirrorAxis.Point(x, y);
    }

    @Test
    void translationAndRotationChangeReflectionWithoutChangingThePoints() {
        ArtMeshMirrorAxis axis = new ArtMeshMirrorAxis(p(0, 0), false);
        axis.translate(p(10, 20));
        assertPoint(p(8, 23), axis.reflect(p(12, 23)));
        axis.rotate(p(11, 21));
        assertPoint(p(13, 22), axis.reflect(p(12, 23)));
        assertPoint(p(3, 2), axis.reflectVector(p(2, 3)));
    }

    @Test
    void slidingThePivotDoesNotChangeTheMirrorLine() {
        ArtMeshMirrorAxis axis = new ArtMeshMirrorAxis(p(2, 3), false);
        var before = axis.reflect(p(8, 10));
        axis.slidePivot(p(30, 40));
        assertPoint(p(2, 40), axis.pivot());
        assertPoint(before, axis.reflect(p(8, 10)));
    }

    @Test
    void rotationSnapsNearVerticalAndKeepsOtherAngles() {
        ArtMeshMirrorAxis axis = new ArtMeshMirrorAxis(p(0, 0), true);
        axis.rotate(p(0.01f, 1));
        assertEquals(Math.PI / 2, axis.angle(), 1e-6);
        axis.rotate(p(1, 1));
        assertEquals(Math.PI / 4, axis.angle(), 1e-6);
    }

    @Test
    void pairsRemainAStableGestureSnapshotAfterVerticesMove() {
        ArtMeshMirrorAxis axis = new ArtMeshMirrorAxis(p(0, 0), false);
        var points = new ArtMeshMirrorAxis.Point[] {p(-1, 0), p(1, 0), p(0, 1), p(4, 3)};
        int[] pairs = axis.pair(points, .01f);
        points[0] = p(-30, 15);
        assertArrayEquals(new int[] {1, 0, -1, -1}, pairs);
    }

    @Test
    void diagonalPairsAndDuplicateVerticesAreHandled() {
        ArtMeshMirrorAxis axis = new ArtMeshMirrorAxis(p(0, 0), false);
        axis.rotate(p(1, 1));
        assertArrayEquals(new int[] {1, 0}, axis.pair(new ArtMeshMirrorAxis.Point[] {p(1, 2), p(2, 1)}, .01f));
        axis.preset(false);
        assertArrayEquals(
                new int[] {-1, -1, -1}, axis.pair(new ArtMeshMirrorAxis.Point[] {p(-1, 0), p(1, 0), p(1, 0)}, .01f));
    }

    @Test
    void pairsAndDisplacementsFollowArbitraryAnglesAndTranslatedPivots() {
        for (int degrees = -179; degrees < 180; degrees += 7) {
            var pivot = p(137, -52);
            var axis = new ArtMeshMirrorAxis(pivot, false);
            double radians = Math.toRadians(degrees);
            axis.rotate(p(pivot.x() + (float) Math.cos(radians) * 100, pivot.y() + (float) Math.sin(radians) * 100));
            var source = p(173, -18);
            var mirrored = axis.reflect(source);
            if (Math.hypot(source.x() - mirrored.x(), source.y() - mirrored.y()) < .1) continue;
            assertArrayEquals(
                    new int[] {1, 0},
                    axis.pair(new ArtMeshMirrorAxis.Point[] {source, mirrored}, .01f),
                    "angle=" + degrees);
            var delta = p(11, -7);
            var expected = axis.reflect(source.plus(delta));
            var actual = mirrored.plus(axis.reflectVector(delta));
            // Translated float coordinates accumulate several ULPs through the two paths.
            assertEquals(expected.x(), actual.x(), 1e-4, "angle=" + degrees);
            assertEquals(expected.y(), actual.y(), 1e-4, "angle=" + degrees);
        }
    }

    @Test
    void axisStoreIsolatesModelsRejectsCorruptFilesAndPrunesEmptyModels(@TempDir Path tempDir) throws Exception {
        try (ArtMeshMirrorAxisStore store = ArtMeshMirrorAxisStore.at(tempDir)) {
            var meshOne = new ArtMeshMirrorAxisStore.SavedAxis(12.5f, -8.25f, 0.75, true);
            var meshTwo = new ArtMeshMirrorAxisStore.SavedAxis(-4.0f, 33.0f, Math.PI / 2.0, false);
            var otherModelMeshOne = new ArtMeshMirrorAxisStore.SavedAxis(99.0f, 100.0f, 0.0, false);
            store.put("model-a", "mesh-1", meshOne);
            store.put("model-a", "mesh-2", meshTwo);
            store.put("model-b", "mesh-1", otherModelMeshOne);
            store.put("model-c", "mesh-1", meshOne);
            store.flush();
            assertTrue(Files.isRegularFile(store.fileForModel("model-c")));
            store.remove("model-c", "mesh-1");
            store.flush();
            assertFalse(Files.exists(store.fileForModel("model-c")));
        }

        try (ArtMeshMirrorAxisStore reloaded = ArtMeshMirrorAxisStore.at(tempDir)) {
            assertEquals(
                    new ArtMeshMirrorAxisStore.SavedAxis(12.5f, -8.25f, 0.75, true), reloaded.get("model-a", "mesh-1"));
            assertEquals(
                    new ArtMeshMirrorAxisStore.SavedAxis(-4.0f, 33.0f, Math.PI / 2.0, false),
                    reloaded.get("model-a", "mesh-2"));
            assertEquals(
                    new ArtMeshMirrorAxisStore.SavedAxis(99.0f, 100.0f, 0.0, false), reloaded.get("model-b", "mesh-1"));
            assertNull(reloaded.get("model-a", "unknown"));

            Files.writeString(reloaded.fileForModel("model-a"), "{corrupt");
            Files.writeString(
                    reloaded.fileForModel("model-b"), "{\"schemaVersion\":99,\"modelGuid\":\"model-b\",\"axes\":{}}");
        }

        try (ArtMeshMirrorAxisStore failedSafe = ArtMeshMirrorAxisStore.at(tempDir)) {
            assertNull(failedSafe.get("model-a", "mesh-1"));
            assertNull(failedSafe.get("model-b", "mesh-1"));
            Files.writeString(
                    failedSafe.fileForModel("model-b"),
                    "{\"schemaVersion\":1,\"modelGuid\":\"wrong-model\",\"axes\":{\"mesh-1\":{\"pivotX\":1,\"pivotY\":2,\"angle\":0,\"locked\":true}}}");
        }

        try (ArtMeshMirrorAxisStore mismatchedModel = ArtMeshMirrorAxisStore.at(tempDir)) {
            assertNull(mismatchedModel.get("model-b", "mesh-1"));
        }
    }

    private static void assertPoint(ArtMeshMirrorAxis.Point expected, ArtMeshMirrorAxis.Point actual) {
        assertEquals(expected.x(), actual.x(), 1e-5);
        assertEquals(expected.y(), actual.y(), 1e-5);
    }
}
