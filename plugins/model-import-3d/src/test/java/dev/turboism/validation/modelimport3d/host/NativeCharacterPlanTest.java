package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class NativeCharacterPlanTest {

    private static Path resolveFixturePath() {
        String fixture = System.getenv("TURBOISM_VIEWER_FIXTURE");
        if (fixture == null || fixture.isBlank()) {
            fixture = System.getProperty("turboism.viewer.fixture");
        }
        if (fixture == null || fixture.isBlank()) {
            return null;
        }
        final Path path = Path.of(fixture);
        return Files.exists(path) ? path : null;
    }

    private static SurfaceImport.Request defaultRequest() {
        return new SurfaceImport.Request(
                "Surtr",
                new ParameterId("ParamAngleY"),
                new ParameterId("ParamAngleX"),
                new SurfaceProjection.Camera(512, 512, 400.0, new SurfaceProjection.Vertex(0, 0.7, 0)),
                180,
                180,
                20_000_000,
                Optional.of(new ParameterId("ParamAngleZ")));
    }

    @Test
    void screenOverlapOverridesMisleadingWholeMeshCenterDepth() {
        // The remote rear triangle biases this mesh's center behind the other mesh.
        // Only its local front triangle overlaps the other mesh in the neutral view.
        var front = new ViewerScene.Mesh(
                "Front with remote rear surface",
                new float[] {-1, -1, 1, 1, -1, 1, 0, 1, 1, 9, -1, -10, 11, -1, -10, 10, 1, -10},
                null,
                new float[12],
                null,
                null,
                new int[] {0, 1, 2, 3, 4, 5},
                0);
        var rear = new ViewerScene.Mesh(
                "Rear",
                new float[] {-1, -1, 0, 1, -1, 0, 0, 1, 0},
                null,
                new float[6],
                null,
                null,
                new int[] {0, 1, 2},
                0);
        var material = new ViewerScene.Material(
                "Opaque", new float[] {1, 1, 1, 1}, -1, -1, new float[3], "OPAQUE", 0.5f, true, true);
        var scene = new ViewerScene(
                List.of(front, rear),
                List.of(material),
                List.of(),
                new ViewerScene.Bounds(-1, -1, -10, 11, 1, 1),
                new ViewerScene.Stats(0, 2, 3, 1, 0, 0, 0));
        var candidate = NativeCharacterPlan.prepareCandidate(scene, defaultRequest(), Map.of());
        assertTrue(candidate.geometry().orders().isEmpty(), "Rejected fitting candidates must not compile visibility");
        var plan = candidate.finish();
        assertEquals(
                NativeCharacterPlan.prepare(scene, defaultRequest()),
                plan,
                "Final ordering, geometry, camera and every budget must be identical");
        // Disconnected surfaces now become separate patches; the local front surface
        // must still cover mesh 1, independently of its remote rear surface.
        assertEquals(3, plan.partitions().size());
        int frontPatch = plan.partitions().stream()
                .filter(p -> p.sourceMeshIndex() == 0 && p.center().z() > 0)
                .map(p -> plan.partitions().indexOf(p))
                .findFirst()
                .orElseThrow();
        int rearPatch = plan.partitions().stream()
                .filter(p -> p.sourceMeshIndex() == 1)
                .map(p -> plan.partitions().indexOf(p))
                .findFirst()
                .orElseThrow();
        assertTrue(plan.orders().get(12).get(frontPatch) > plan.orders().get(12).get(rearPatch));
        assertEquals(3, plan.totalTriangles());
    }

    @Test
    void chordErrorCalculatesSagittaForNinetyDegreeStep() {
        // Sagitta formula: R * (1 - cos(45 / (inserts + 1)))
        assertEquals(0.0, NativeCharacterPlan.chordError(0.0, 0));
        assertEquals(0.0, NativeCharacterPlan.chordError(100.0, -1)); // 0 or invalid radius

        double r = 200.0;
        // inserts = 0: sub-interval is 90 deg, sagitta half-angle is 45 deg
        double expected0 = r * (1.0 - Math.cos(Math.toRadians(45.0)));
        assertEquals(expected0, NativeCharacterPlan.chordError(r, 0), 1e-6);

        // inserts = 1: sub-interval is 45 deg, half-angle 22.5 deg
        double expected1 = r * (1.0 - Math.cos(Math.toRadians(22.5)));
        assertEquals(expected1, NativeCharacterPlan.chordError(r, 1), 1e-6);

        // inserts = 3: sub-interval is 22.5 deg, half-angle 11.25 deg
        double expected3 = r * (1.0 - Math.cos(Math.toRadians(11.25)));
        assertEquals(expected3, NativeCharacterPlan.chordError(r, 3), 1e-6);
        assertTrue(expected3 < 4.0); // Bounded within 4 pixels for r=200
    }

    @Test
    void samplingSelectsOptimalInsertsWithinBudget() {
        double maxBudget = 5.0;
        var s = NativeCharacterPlan.sampling(80.0, maxBudget);
        assertNotNull(s);
        assertTrue(s.errorBoundPixels() <= maxBudget);
        assertTrue(s.grid() <= 289L);

        // When error budget is tighter than achievable with maxInserts=3, sampling throws explicitly
        var ex = assertThrows(IllegalArgumentException.class, () -> NativeCharacterPlan.sampling(400.0, 5.0));
        assertTrue(ex.getMessage().contains("Interpolation error bound cannot satisfy"));

        // Achievable bound for R=400 with 3 inserts is ~15.38px
        var s400 = NativeCharacterPlan.sampling(400.0, 16.0);
        assertNotNull(s400);
        assertTrue(s400.errorBoundPixels() <= 16.0);
    }

    @Test
    void sharedVerticesAcrossDifferentRadiusMeshesUseIdenticalRotationInterpolation() {
        var near = new ViewerScene.Mesh(
                "Near pivot",
                new float[] {.2f, 0, .1f, .1f, .05f, .1f, .2f, .05f, .1f},
                null,
                new float[6],
                null,
                null,
                new int[] {0, 1, 2},
                -1);
        var outer = new ViewerScene.Mesh(
                "Outer shell",
                new float[] {.2f, 0, .1f, -1, -1, -1, 1, 1, 1},
                null,
                new float[6],
                null,
                null,
                new int[] {0, 1, 2},
                -1);
        var scene = new ViewerScene(
                List.of(near, outer),
                List.of(),
                List.of(),
                new ViewerScene.Bounds(-1, -1, -1, 1, 1, 1),
                new ViewerScene.Stats(0, 2, 2, 0, 0, 0, 0));
        // This fixture tests shared geometry interpolation, independently of visibility.
        // Its intersecting faces cannot always be represented by one global painter order.
        var plan = NativeCharacterPlan.prepareCandidate(scene, defaultRequest(), Map.of())
                .geometry();
        assertEquals(2, plan.partitions().size());
        assertEquals(
                plan.partitions().get(0).positions().get(0),
                plan.partitions().get(1).positions().get(0));
        assertEquals(
                plan.sampling().get(0).yaw(),
                plan.sampling().get(1).yaw(),
                "Different interpolation densities separate the same source vertex between samples");
        assertEquals(plan.sampling().get(0).pitch(), plan.sampling().get(1).pitch());
        assertEquals(6L * 289, plan.totalCacheSlots());
    }

    @Test
    void syntheticMeshPlanPreservesExactTrianglesAndSourceSlots() {
        // Create a synthetic tetrahedron
        float[] pos = {0f, 1f, 0f, -1f, -1f, 1f, 1f, -1f, 1f, 0f, -1f, -1f};
        float[] uvs = {
            0.5f, 0f,
            0f, 1f,
            1f, 1f,
            0.5f, 0.5f
        };
        int[] ind = {
            0, 1, 2,
            0, 2, 3,
            0, 3, 1,
            1, 3, 2
        };

        var mesh = new ViewerScene.Mesh("Tetrahedron", pos, new float[12], uvs, null, null, ind, 0);
        var bounds = new ViewerScene.Bounds(-1f, -1f, -1f, 1f, 1f, 1f);
        var stats = new ViewerScene.Stats(100, 1, 4, 1, 0, 0, 0);
        var scene = new ViewerScene(List.of(mesh), List.of(), List.of(), bounds, stats);

        var plan = NativeCharacterPlan.prepare(scene, defaultRequest());

        assertNotNull(plan);
        assertEquals(4, plan.totalSourceSlots());
        assertEquals(4, plan.totalPartitionSlots());
        assertEquals(4, plan.totalTriangles());
        assertEquals(1, plan.partitions().size());
        assertEquals(25, plan.orders().size());
        assertEquals(25 * 4, plan.totalKeyformSlots());
        assertTrue(plan.totalKeyformSlots() <= NativeCharacterPlan.MAX_TOTAL_KEYFORM_SLOTS);

        var part = plan.partitions().get(0);
        assertEquals(4, part.positions().size());
        assertEquals(12, part.indices().size());
        assertEquals(List.of(0, 1, 2, 3), part.sourceSlotMapping());
    }

    @Test
    void actualFixtureBoundedGeometryPlanSatisfiesAllBudgets() throws Exception {
        final Path fixturePath = resolveFixturePath();
        Assumptions.assumeTrue(fixturePath != null, "Actual GLB fixture not found, skipping fixture test");

        byte[] bytes = Files.readAllBytes(fixturePath);
        ViewerScene scene = GlbViewerLoader.load(bytes, () -> false);
        assertNotNull(scene);

        var request = defaultRequest();
        long start = System.currentTimeMillis();
        var plan = NativeCharacterPlan.prepare(scene, request);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Actual fixture plan generated in %d ms%n", elapsed);
        System.out.printf("  Source slots: %d%n", plan.totalSourceSlots());
        System.out.printf("  Source triangles: %d%n", plan.totalTriangles());
        System.out.printf("  Partitions: %d%n", plan.partitions().size());
        System.out.printf("  Total partition slots: %d%n", plan.totalPartitionSlots());
        System.out.printf(
                "  Total 25-form keyform slots: %d (max budget: %d)%n",
                plan.totalKeyformSlots(), NativeCharacterPlan.MAX_TOTAL_KEYFORM_SLOTS);
        System.out.printf("  Max interpolation error: %.4f px%n", plan.maxInterpolationErrorPixels());

        // 1. Verify exact source numbers
        assertEquals(93927, plan.totalSourceSlots(), "Source slots must match exactly 93927");
        assertEquals(121097, plan.totalTriangles(), "Triangles must match exactly 121097");

        // 2. Verify zero dropped faces
        long totalPartTriangles = plan.partitions().stream()
                .mapToLong(NativeCharacterPlan.Partition::triangleCount)
                .sum();
        assertEquals(121097, totalPartTriangles, "All triangles strictly preserved across partitions");

        // 3. Verify total partition slots and 25-form save budget
        // Partitions duplicate only boundary vertices
        assertEquals(155, plan.partitions().size(), "Partitions count for 1200 max triangles");
        assertEquals(105204, plan.totalPartitionSlots(), "Quantified partition slots");
        long expectedKeyformSlots = 105204L * 25L;
        assertEquals(expectedKeyformSlots, plan.totalKeyformSlots());
        assertTrue(
                plan.totalKeyformSlots() <= NativeCharacterPlan.MAX_TOTAL_KEYFORM_SLOTS,
                "Keyform slots must strictly fit within 6M budget");
        assertTrue(
                plan.totalKeyformSlots() < 3_000_000L,
                "25 forms easily fit in <3M slots (actual " + plan.totalKeyformSlots() + ")");

        // Cache slots budget verification
        assertEquals(
                plan.partitions().stream()
                        .mapToLong(p -> p.positions().size() * (p.visible() ? 289L : 1L))
                        .sum(),
                plan.totalCacheSlots(),
                "Shared visible lattice retains complete cache preflight");
        assertTrue(
                plan.totalCacheSlots() <= NativeCharacterPlan.MAX_CACHE_SLOTS,
                "Cache slots must strictly fit within 64M budget");

        // Camera framing verification
        assertTrue(plan.camera().scale() > 0, "Camera scale must be positive");
        assertTrue(plan.maxInterpolationErrorPixels() <= 15.40, "Max chord error bound on 400px framing is <= 15.40px");
        assertEquals(15.3818, plan.maxInterpolationErrorPixels(), 1e-3, "Exact theoretical chord error bound");

        // Material double-sided preservation
        long doubleSidedCount = plan.partitions().stream()
                .filter(NativeCharacterPlan.Partition::doubleSided)
                .count();
        long singleSidedCount =
                plan.partitions().stream().filter(p -> !p.doubleSided()).count();
        assertEquals(152, doubleSidedCount, "23 meshes with materials are double-sided");
        assertEquals(3, singleSidedCount, "3 meshes without materials remain single-sided");

        // 4. Verify 5x5 keys
        assertEquals(List.of(-180f, -90f, 0f, 90f, 180f), plan.keys());

        // 5. Verify dynamic draw orders
        assertEquals(25, plan.orders().size(), "Must have exactly 25 keyform drawOrder frames");
        for (var orderList : plan.orders()) {
            assertEquals(plan.partitions().size(), orderList.size());
            for (int order : orderList) {
                assertTrue(order >= 0 && order <= 1000, "Draw order in 0..1000 range");
            }
        }

        // 6. Verify each partition has valid local indices within [0, partition.positions().size() - 1]
        for (var part : plan.partitions()) {
            int vCount = part.positions().size();
            for (int idx : part.indices()) {
                assertTrue(idx >= 0 && idx < vCount, "Partition local index out of bounds");
            }
            assertEquals(part.triangleCount() * 3, part.indices().size());
            assertEquals(vCount, part.uvs().size());
            assertEquals(vCount, part.sourceSlotMapping().size());
        }
    }

    @Test
    void drawOrderInvertsAcrossOpposingRotationAngles() {
        // Create two meshes: one in front (Z = 2) and one behind (Z = -2), as in SoftwareModelRenderer
        float[] posA = {-1f, -1f, 2f, 1f, -1f, 2f, 0f, 1f, 2f};
        float[] posB = {-1f, -1f, -2f, 1f, -1f, -2f, 0f, 1f, -2f};
        float[] uvs = {0f, 0f, 1f, 0f, 0.5f, 1f};
        int[] ind = {0, 1, 2};

        var meshA = new ViewerScene.Mesh("FrontMesh", posA, new float[9], uvs, null, null, ind, 0);
        var meshB = new ViewerScene.Mesh("BackMesh", posB, new float[9], uvs, null, null, ind, 1);
        var bounds = new ViewerScene.Bounds(-1f, -1f, -2f, 1f, 1f, 2f);
        var stats = new ViewerScene.Stats(100, 2, 2, 2, 0, 0, 0);
        var red = new ViewerScene.Material(
                "Red front", new float[] {1, 0, 0, 1}, -1, -1, new float[3], "OPAQUE", .5f, true, true);
        var blue = new ViewerScene.Material(
                "Blue back", new float[] {0, 0, 1, 1}, -1, -1, new float[3], "OPAQUE", .5f, true, true);
        var scene = new ViewerScene(List.of(meshA, meshB), List.of(red, blue), List.of(), bounds, stats);
        var reference = new dev.turboism.plugin.modelimport3d.viewer.SoftwareModelRenderer()
                .renderWithStats(scene, 128, 128, 0, 0, 1);
        assertEquals(
                0xff0000,
                reference.image().getRGB(64, 64) & 0xffffff,
                "The independent Z-buffer renderer sees the +Z red plane in front");

        var plan = NativeCharacterPlan.prepare(scene, defaultRequest());
        assertEquals(2, plan.partitions().size());

        // At Yaw = 0, Pitch = 0 (key index: 2 * 5 + 2 = 12)
        int neutralKeyIdx = NativeCharacterPlan.KEYS.indexOf(0f) * 5 + NativeCharacterPlan.KEYS.indexOf(0f);
        int orderA_neutral = plan.orders().get(neutralKeyIdx).get(0);
        int orderB_neutral = plan.orders().get(neutralKeyIdx).get(1);

        // At neutral, meshA (Z=2) is nearer to camera than meshB (Z=-2)
        // In painter's algorithm, nearer objects get higher drawOrder
        assertTrue(orderA_neutral > orderB_neutral, "At Yaw=0, front mesh must have higher drawOrder than back mesh");

        // At Yaw = 180, Pitch = 0 (key index: 4 * 5 + 2 = 22)
        int rotatedKeyIdx = NativeCharacterPlan.KEYS.indexOf(180f) * 5 + NativeCharacterPlan.KEYS.indexOf(0f);
        int orderA_rot = plan.orders().get(rotatedKeyIdx).get(0);
        int orderB_rot = plan.orders().get(rotatedKeyIdx).get(1);

        // After 180 degree yaw rotation, meshB becomes nearer to camera than meshA!
        assertTrue(orderB_rot > orderA_rot, "At Yaw=180, back mesh rotated to front must have higher drawOrder");
    }

    @Test
    void prepareRejectsSceneExceedingKeyformSlotBudget() {
        int meshesCount = 250;
        int vPerMesh = 1000;
        var meshes = new ArrayList<ViewerScene.Mesh>();
        for (int m = 0; m < meshesCount; m++) {
            float[] pos = new float[vPerMesh * 3];
            pos[0] = -1f;
            pos[1] = -1f;
            pos[3] = 1f;
            pos[4] = 1f;
            float[] uvs = new float[vPerMesh * 2];
            int tCount = vPerMesh - 2;
            int[] ind = new int[tCount * 3];
            for (int i = 0; i < tCount; i++) {
                ind[i * 3] = 0;
                ind[i * 3 + 1] = i + 1;
                ind[i * 3 + 2] = i + 2;
            }
            meshes.add(new ViewerScene.Mesh("Mesh" + m, pos, new float[vPerMesh * 3], uvs, null, null, ind, 0));
        }

        var bounds = new ViewerScene.Bounds(-1, -1, 0, 1, 1, 1);
        var stats = new ViewerScene.Stats(100, meshesCount, (vPerMesh - 2) * meshesCount, 1, 0, 0, 0);
        var scene = new ViewerScene(meshes, List.of(), List.of(), bounds, stats);

        var ex = assertThrows(
                IllegalArgumentException.class, () -> NativeCharacterPlan.prepare(scene, defaultRequest()));
        assertTrue(ex.getMessage().contains("6M save budget"));
    }
}
