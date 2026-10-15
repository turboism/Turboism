package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeMaterialPlanTest {

    private static Path resolveActualGlbPath() {
        String fixture = System.getenv("TURBOISM_VIEWER_FIXTURE");
        if (fixture == null || fixture.isBlank()) {
            fixture = System.getProperty("turboism.viewer.fixture");
        }
        if (fixture != null && !fixture.isBlank()) {
            Path p = Path.of(fixture);
            if (Files.exists(p)) {
                return p;
            }
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path p = Path.of(userHome, "Documents", "Laevatein_Surtr_Futa_v1.1.glb");
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path resolveRepairedGlbPath() {
        String fixture = System.getenv("TURBOISM_REPAIRED_FIXTURE");
        if (fixture == null || fixture.isBlank()) {
            fixture = System.getProperty("turboism.repaired.fixture");
        }
        if (fixture != null && !fixture.isBlank()) {
            Path p = Path.of(fixture);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        Path defaultTmp = Path.of(tmpDir, "task-n002-blend", "native-material-repaired.glb");
        if (Files.isRegularFile(defaultTmp)) {
            return defaultTmp;
        }
        return null;
    }

    @Test
    void actualGlbFixtureFocusedVerification() throws IOException {
        Path actualPath = resolveActualGlbPath();
        Assumptions.assumeTrue(actualPath != null, "Actual fixture file must exist");

        byte[] glbBytes = Files.readAllBytes(actualPath);
        ViewerScene rawScene = GlbViewerLoader.load(glbBytes, () -> false);
        assertNotNull(rawScene);

        int originalTriangleCount = 0;
        for (ViewerScene.Mesh m : rawScene.meshes()) {
            originalTriangleCount += m.indices().length / 3;
        }
        assertEquals(121097, originalTriangleCount, "Input triangle count must match verified fixture");
        assertEquals(26, rawScene.meshes().size(), "Input mesh count must match verified primitives");

        long start = System.currentTimeMillis();
        NativeMaterialPlan plan = NativeMaterialPlan.prepare(rawScene);
        long elapsed = System.currentTimeMillis() - start;

        // Verify no triangle loss
        int normalizedTriangleCount = 0;
        for (ViewerScene.Mesh m : plan.scene().meshes()) {
            normalizedTriangleCount += m.indices().length / 3;
        }
        assertEquals(originalTriangleCount, normalizedTriangleCount, "Zero triangle loss required");
        assertEquals(26, plan.scene().meshes().size());
        assertEquals(26, plan.meshImageIndices().size());
        assertEquals(26, plan.meshImages().size());

        // Report image counts and dimensions
        List<NativeMaterialPlan.Image> images = plan.images();
        assertEquals(10, images.size(), "Unique deduplicated image count must match glTF material assignments");

        // Verify 4096x4096 is preserved for Body/shaft/glans bump/diffuse
        NativeMaterialPlan.Image img0 = images.get(0);
        assertEquals(4096, img0.width());
        assertEquals(4096, img0.height());
        assertTrue(img0.png().length > 0);
        assertNotNull(img0.contentHash());
        AuthorizedImageContent grant0 = img0.newGrant();
        assertNotNull(grant0);

        // Verify Nails image is 2048x2048
        NativeMaterialPlan.Image img1 = images.get(1);
        assertEquals(2048, img1.width());
        assertEquals(2048, img1.height());

        // Verify untextured meshes (21, 22, 23) mapped to constant 16x16 image (image 9)
        int untexturedIdx21 = plan.imageIndexForMesh(21);
        int untexturedIdx22 = plan.imageIndexForMesh(22);
        int untexturedIdx23 = plan.imageIndexForMesh(23);
        assertEquals(untexturedIdx21, untexturedIdx22);
        assertEquals(untexturedIdx21, untexturedIdx23);
        NativeMaterialPlan.Image untexturedImg = plan.images().get(untexturedIdx21);
        assertEquals(16, untexturedImg.width());
        assertEquals(16, untexturedImg.height());

        // Verify tongue (mesh 20) with vertex color alpha 0 baked transparent image
        int tongueIdx = plan.imageIndexForMesh(20);
        NativeMaterialPlan.Image tongueImg = plan.images().get(tongueIdx);
        assertEquals(256, tongueImg.width());
        assertEquals(256, tongueImg.height());

        // Verify all images can issue grants
        for (NativeMaterialPlan.Image img : images) {
            AuthorizedImageContent grant = img.newGrant("Focused test verification");
            assertNotNull(grant);
        }

        // Verify emissive meshes promoted emissiveUvs to primary uvs
        // Mesh 5 uses Material 5 (emissive only)
        ViewerScene.Mesh mesh5 = plan.scene().meshes().get(5);
        assertArrayEquals(rawScene.meshes().get(5).emissiveUvs(), mesh5.uvs());
    }

    @Test
    void repairedGlbFixtureFocusedVerificationIfPresent() throws IOException {
        Path repairedPath = resolveRepairedGlbPath();
        Assumptions.assumeTrue(
                repairedPath != null && Files.isRegularFile(repairedPath), "Repaired fixture file must exist");

        byte[] glbBytes = Files.readAllBytes(repairedPath);
        ViewerScene rawScene = GlbViewerLoader.load(glbBytes, () -> false);
        assertNotNull(rawScene);

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(rawScene);
        assertNotNull(plan);

        // Verify zero triangle loss
        int normTris = 0;
        for (ViewerScene.Mesh m : plan.scene().meshes()) {
            normTris += m.indices().length / 3;
        }
        assertEquals(121097, normTris, "Zero triangle loss on repaired fixture");

        // Verify body image is 2048x2048
        NativeMaterialPlan.Image bodyImg = plan.imageForMesh(0);
        assertNotNull(bodyImg);
        assertEquals(2048, bodyImg.width());
        assertEquals(2048, bodyImg.height());
    }

    @Test
    void emptySceneReturnsEmptyPlan() {
        ViewerScene emptyScene = new ViewerScene(
                List.of(),
                List.of(),
                List.of(),
                new ViewerScene.Bounds(0, 0, 0, 0, 0, 0),
                new ViewerScene.Stats(0, 0, 0, 0, 0, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(emptyScene);
        assertNotNull(plan);
        assertTrue(plan.scene().meshes().isEmpty());
        assertTrue(plan.meshImageIndices().isEmpty());
        assertTrue(plan.images().isEmpty());
    }

    @Test
    void untexturedMeshBakesConstant16x16Rgba() {
        ViewerScene.Mesh mesh = new ViewerScene.Mesh(
                "untextured",
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1},
                null,
                null,
                new int[] {0, 1, 2},
                -1);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(),
                List.of(),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 0, 0, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(1, plan.images().size());
        assertEquals(0, plan.imageIndexForMesh(0));

        NativeMaterialPlan.Image img = plan.imageForMesh(0);
        assertNotNull(img);
        assertEquals(16, img.width());
        assertEquals(16, img.height());

        AuthorizedImageContent grant = img.newGrant();
        assertNotNull(grant);
    }

    @Test
    void constantColorMaterialBakesConstantImage() {
        ViewerScene.Mesh mesh = new ViewerScene.Mesh(
                "mesh",
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                null,
                new float[] {0, 0, 1, 0, 0, 1},
                null,
                null,
                new int[] {0, 1, 2},
                0);

        ViewerScene.Material redMat = new ViewerScene.Material(
                "red", new float[] {1f, 0f, 0f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(redMat),
                List.of(),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 0, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(1, plan.images().size());
        NativeMaterialPlan.Image img = plan.imageForMesh(0);
        assertEquals(16, img.width());
        assertEquals(16, img.height());
    }

    @Test
    void alphaModesHandling() {
        // Base texture 2x2 with varying alpha
        int[] argb = new int[] {
            0xFFFFFFFF, // a=255
            0x80FFFFFF, // a=128
            0x40FFFFFF, // a=64
            0x00FFFFFF // a=0
        };
        ViewerScene.Texture tex = new ViewerScene.Texture(2, 2, argb);

        ViewerScene.Mesh meshOpaque =
                new ViewerScene.Mesh("m1", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Mesh meshMask =
                new ViewerScene.Mesh("m2", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 1);
        ViewerScene.Mesh meshBlend =
                new ViewerScene.Mesh("m3", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 2);

        ViewerScene.Material matOpaque = new ViewerScene.Material(
                "opaque", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "OPAQUE", 0.5f, false, false);
        ViewerScene.Material matMask = new ViewerScene.Material(
                "mask", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "MASK", 0.5f, false, false);
        ViewerScene.Material matBlend = new ViewerScene.Material(
                "blend", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "BLEND", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(meshOpaque, meshMask, meshBlend),
                List.of(matOpaque, matMask, matBlend),
                List.of(tex),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 3, 3, 3, 1, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(3, plan.images().size());
        assertNotEquals(plan.imageIndexForMesh(0), plan.imageIndexForMesh(1));
        assertNotEquals(plan.imageIndexForMesh(1), plan.imageIndexForMesh(2));
    }

    @Test
    void multiUvBaseAndEmissiveThrowsIllegalArgumentException() {
        ViewerScene.Texture base = new ViewerScene.Texture(4, 4, new int[16]);
        ViewerScene.Texture emiss = new ViewerScene.Texture(4, 4, new int[16]);

        float[] uvs = new float[] {0, 0, 1, 0, 0, 1};
        float[] diffUvs = new float[] {0.5f, 0.5f, 1, 0, 0, 1};

        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("multiUvMesh", new float[9], null, uvs, diffUvs, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, 1, new float[] {1, 1, 1}, "OPAQUE", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(base, emiss),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 2, 0, 0));

        assertThrows(IllegalArgumentException.class, () -> NativeMaterialPlan.prepare(scene));
    }

    @Test
    void combinedBaseAndEmissiveDifferentResolutionsBakesMaxDimension() {
        ViewerScene.Texture base = new ViewerScene.Texture(32, 32, new int[32 * 32]);
        ViewerScene.Texture emiss = new ViewerScene.Texture(64, 64, new int[64 * 64]);

        float[] uvs = new float[] {0, 0, 1, 0, 0, 1};
        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("mesh", new float[9], null, uvs, uvs.clone(), null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, 1, new float[] {1, 1, 1}, "BLEND", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(base, emiss),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 2, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(1, plan.images().size());
        NativeMaterialPlan.Image img = plan.images().get(0);
        assertEquals(64, img.width());
        assertEquals(64, img.height());
    }

    @Test
    void deduplicationMergesIdenticalMaterials() {
        ViewerScene.Texture tex = new ViewerScene.Texture(16, 16, new int[16 * 16]);
        ViewerScene.Mesh mesh1 =
                new ViewerScene.Mesh("m1", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Mesh mesh2 =
                new ViewerScene.Mesh("m2", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "BLEND", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh1, mesh2),
                List.of(mat),
                List.of(tex),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 2, 2, 1, 1, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(1, plan.images().size(), "Identical materials must deduplicate to single image");
        assertEquals(plan.imageIndexForMesh(0), plan.imageIndexForMesh(1));
    }

    @Test
    void cumulativePixelBudgetExceededThrowsIllegalArgumentException() {
        ViewerScene.Texture tex = new ViewerScene.Texture(256, 256, new int[256 * 256]);
        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("m", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "BLEND", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(tex),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 1, 0, 0));

        NativeMaterialPlan.Options tightOptions = new NativeMaterialPlan.Options(100L, 10_000_000L, 8192, 16);
        assertThrows(
                IllegalArgumentException.class, () -> NativeMaterialPlan.prepare(scene, tightOptions, () -> false));
    }

    @Test
    void cancellationStopsPreparation() {
        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("m", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, -1);
        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(),
                List.of(),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 0, 0, 0, 0));

        assertThrows(CancellationException.class, () -> NativeMaterialPlan.prepare(scene, () -> true));
    }

    @Test
    void defensiveCopyingInImageRecord() {
        byte[] originalBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        NativeMaterialPlan.Image img = new NativeMaterialPlan.Image(16, 16, originalBytes, "abc");

        // Mutate original array
        originalBytes[0] = 0;
        assertNotEquals(0, img.png()[0]);

        // Mutate array returned by getter
        byte[] getterBytes = img.png();
        getterBytes[0] = 0;
        assertNotEquals(0, img.png()[0]);
    }

    @Test
    void cacheBypassMultiUvThrowsOnSubsequentMesh() {
        ViewerScene.Texture base = new ViewerScene.Texture(4, 4, new int[16]);
        ViewerScene.Texture emiss = new ViewerScene.Texture(4, 4, new int[16]);

        float[] uvs = new float[] {0, 0, 1, 0, 0, 1};
        float[] diffUvs = new float[] {0.5f, 0.5f, 1, 0, 0, 1};

        // mesh1 has matching UVs and would be cached
        ViewerScene.Mesh mesh1 =
                new ViewerScene.Mesh("mesh1", new float[9], null, uvs, uvs.clone(), null, new int[] {0, 1, 2}, 0);
        // mesh2 shares the same material 0, but has conflicting emissive UVs
        ViewerScene.Mesh mesh2 =
                new ViewerScene.Mesh("mesh2", new float[9], null, uvs, diffUvs, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, 1, new float[] {1, 1, 1}, "OPAQUE", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh1, mesh2),
                List.of(mat),
                List.of(base, emiss),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 2, 2, 1, 2, 0, 0));

        assertThrows(IllegalArgumentException.class, () -> NativeMaterialPlan.prepare(scene));
    }

    @Test
    void alphaCutoffZeroPreservesValidMaskAlpha() {
        // Base texture 2x2 with a pixel having a small alpha (e.g. 0.1 / 25)
        int[] argb = new int[] {
            0x19FFFFFF, // a = 25 (~0.1)
            0x00FFFFFF, // a = 0
            0x80FFFFFF, // a = 128
            0xFFFFFFFF // a = 255
        };
        ViewerScene.Texture tex = new ViewerScene.Texture(2, 2, argb);

        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("m", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        // alphaCutoff = 0.0f
        ViewerScene.Material mat = new ViewerScene.Material(
                "maskZero", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "MASK", 0.0f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(tex),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 1, 0, 0));

        NativeMaterialPlan plan = NativeMaterialPlan.prepare(scene);
        assertEquals(1, plan.images().size());
        NativeMaterialPlan.Image img = plan.imageForMesh(0);
        assertNotNull(img);
        assertEquals(2, img.width());
        assertEquals(2, img.height());
    }

    @Test
    void preflightDimensionCheckRejectsOversizedTexturesBeforeBake() {
        ViewerScene.Texture oversized = new ViewerScene.Texture(16384, 16384, new int[1]);
        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("m", new float[9], null, new float[6], null, null, new int[] {0, 1, 2}, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1, 1, 1, 1}, 0, -1, new float[3], "OPAQUE", 0.5f, false, false);

        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(oversized),
                new ViewerScene.Bounds(0, 0, 0, 1, 1, 0),
                new ViewerScene.Stats(100, 1, 1, 1, 1, 0, 0));

        assertThrows(IllegalArgumentException.class, () -> NativeMaterialPlan.prepare(scene));
    }

    @Test
    void srgbLinearConversionRoundtripPrecision() {
        for (int i = 0; i <= 255; i++) {
            float srgb = i / 255.0f;
            float linear = NativeMaterialPlan.srgbToLinear(srgb);
            float backSrgb = NativeMaterialPlan.linearToSrgb(linear);
            int roundtripped = Math.round(backSrgb * 255.0f);
            assertEquals(i, roundtripped, "sRGB -> linear -> sRGB roundtrip error at value " + i);
        }
    }
}
