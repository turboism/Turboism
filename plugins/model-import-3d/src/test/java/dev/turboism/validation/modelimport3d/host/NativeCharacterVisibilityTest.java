package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeCharacterVisibilityTest {
    @Test
    void reusedDepthWorkspaceExactlyMatchesFreshPoseRasterization() {
        var scene = translatedPlan(0, 0);
        int size = 64, n = scene.plan().partitions().size();
        float[][] reused = new float[n][size * size];
        int[][] previous = new int[n][];
        for (float yaw : new float[] {0, 30, 180, -90, -15, 0})
            for (float pitch : new float[] {-90, 0, 30}) {
                var pose = new SurfaceProjection.Pose(yaw, pitch);
                var votes = NativeCharacterVisibility.rasterVotes(
                        scene.plan().partitions(), scene.plan().camera(), scene.scene(), pose, size, reused, previous);
                float[][] fresh = new float[n][size * size];
                NativeCharacterVisibility.order(
                        scene.plan().partitions(), scene.plan().camera(), scene.scene(), pose, size, fresh);
                for (int i = 0; i < n; i++) assertArrayEquals(fresh[i], reused[i], "stale depth pixels at " + pose);
                assertVotesEqual(NativeCharacterVisibility.coverageVotes(fresh, size * size), votes);
            }
    }

    @Test
    void boundedVotingExactlyMatchesFullRasterScan() {
        var random = new Random(441);
        for (int trial = 0; trial < 30; trial++) {
            int size = 41, n = 17;
            float[][] depth = new float[n][size * size];
            int[][] bounds = new int[n][4];
            for (int i = 0; i < n; i++) {
                Arrays.fill(depth[i], Float.NEGATIVE_INFINITY);
                int x = random.nextInt(size), y = random.nextInt(size);
                bounds[i] = new int[] {x, y, x + random.nextInt(size - x), y + random.nextInt(size - y)};
                for (int py = y; py <= bounds[i][3]; py++)
                    for (int px = x; px <= bounds[i][2]; px++)
                        depth[i][py * size + px] = random.nextInt(4) == 0 ? Float.NEGATIVE_INFINITY : random.nextInt(5);
            }
            assertVotesEqual(
                    NativeCharacterVisibility.coverageVotes(depth, size * size),
                    NativeCharacterVisibility.coverageVotes(depth, size, bounds));
        }
    }

    @Test
    void nativeWindingCompensatesProjectionReflectionWithoutMutatingSource() {
        var source = List.of(0, 1, 2, 2, 3, 0);
        assertEquals(List.of(0, 2, 1, 2, 0, 3), NativeCharacterAuthoring.targetWinding(source));
        assertEquals(List.of(0, 1, 2, 2, 3, 0), source);
    }

    @Test
    void reframingTranslatedGeometryPreservesScreenCoverageVotesAndOrder() {
        var original = translatedPlan(0, 0);
        var translated = translatedPlan(20, -30);
        int size = 64;
        for (float yaw : new float[] {-30, 0, 30, 180}) {
            for (float pitch : new float[] {-30, 0, 30}) {
                float[][] before = new float[2][size * size];
                float[][] after = new float[2][size * size];
                var pose = new SurfaceProjection.Pose(yaw, pitch);
                var first = NativeCharacterVisibility.order(
                        original.plan().partitions(), original.plan().camera(), original.scene(), pose, size, before);
                var second = NativeCharacterVisibility.order(
                        translated.plan().partitions(),
                        translated.plan().camera(),
                        translated.scene(),
                        pose,
                        size,
                        after);
                int covered = 0;
                for (int mesh = 0; mesh < before.length; mesh++) {
                    for (int pixel = 0; pixel < before[mesh].length; pixel++) {
                        boolean expected = Float.isFinite(before[mesh][pixel]);
                        if (expected) covered++;
                        assertEquals(
                                expected,
                                Float.isFinite(after[mesh][pixel]),
                                "coverage at " + pose + " mesh=" + mesh + " pixel=" + pixel);
                        if (expected) assertEquals(before[mesh][pixel], after[mesh][pixel], 1e-5f);
                    }
                }
                assertTrue(covered > 0, "Fixture must have real screen coverage");
                assertVotesEqual(
                        NativeCharacterVisibility.coverageVotes(before, size * size),
                        NativeCharacterVisibility.coverageVotes(after, size * size));
                assertEquals(first, second);
            }
        }
    }

    private record FramedScene(ViewerScene scene, NativeCharacterPlan.Plan plan) {}

    private static FramedScene translatedPlan(float dx, float dy) {
        var meshes = new ArrayList<ViewerScene.Mesh>();
        for (float z : new float[] {0, 1}) {
            meshes.add(new ViewerScene.Mesh(
                    "surface",
                    new float[] {dx - 1, dy - 1, z, dx + 1, dy - 1, z, dx, dy + 1, z},
                    null,
                    new float[6],
                    null,
                    null,
                    new int[] {0, 1, 2},
                    0));
        }
        var material = new ViewerScene.Material(
                "opaque", new float[] {1, 1, 1, 1}, -1, -1, new float[3], "OPAQUE", 0.5f, true, true);
        var scene = new ViewerScene(
                meshes,
                List.of(material),
                List.of(),
                new ViewerScene.Bounds(dx - 1, dy - 1, 0, dx + 1, dy + 1, 1),
                new ViewerScene.Stats(0, 2, 2, 1, 0, 0, 0));
        var request = new SurfaceImport.Request(
                "translated",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 100, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                20_000_000,
                Optional.of(new ParameterId("Roll")));
        return new FramedScene(
                scene,
                NativeCharacterPlan.prepareCandidate(scene, request, Map.of()).geometry());
    }

    @Test
    void contiguousVotingMatchesPixelScanForSparseCoverageAndDepthTies() {
        var random = new Random(66);
        for (int partitions : new int[] {0, 1, 2, 17, 129}) {
            float[][] depth = new float[partitions][397];
            for (var row : depth)
                for (int k = 0; k < row.length; k++)
                    row[k] = switch (random.nextInt(6)) {
                        case 0, 1, 2 -> Float.NEGATIVE_INFINITY;
                        case 3 -> 1f; // frequent ties must favor the first partition
                        case 4 -> 1f + random.nextInt(3) * 0.0000005f;
                        default -> random.nextFloat() * 10 - 5;
                    };
            assertVotesEqual(pixelScan(depth, 397), NativeCharacterVisibility.coverageVotes(depth, 397));
        }
    }

    @Test
    void coverageVotingRetainsEmptyPixelsTieThresholdAndNonfiniteBehavior() {
        float[][] depth = {
            {Float.NEGATIVE_INFINITY, 1, 0, Float.NaN, 0, Float.POSITIVE_INFINITY},
            {Float.NEGATIVE_INFINITY, 1, 0.0000005f, 1, 0.000002f, 2},
            {Float.NEGATIVE_INFINITY, 0, -1, 0, 0.000002f, Float.NaN}
        };
        assertVotesEqual(pixelScan(depth, 6), NativeCharacterVisibility.coverageVotes(depth, 6));
        assertVotesEqual(new long[3][3], NativeCharacterVisibility.coverageVotes(new float[3][0], 0));
    }

    private static void assertVotesEqual(long[][] expected, long[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], "partition " + i);
    }

    /** Baseline traversal before the cache-locality change. */
    static long[][] pixelScan(float[][] z, int pixels) {
        int n = z.length;
        long[][] votes = new long[n][n];
        for (int k = 0; k < pixels; k++) {
            int best = -1;
            float dep = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++)
                if (z[i][k] > dep) {
                    dep = z[i][k];
                    best = i;
                }
            if (best < 0) continue;
            for (int i = 0; i < n; i++)
                if (i != best && Float.isFinite(z[i][k]) && z[i][k] < dep - 1e-6) votes[i][best]++;
        }
        return votes;
    }
}
