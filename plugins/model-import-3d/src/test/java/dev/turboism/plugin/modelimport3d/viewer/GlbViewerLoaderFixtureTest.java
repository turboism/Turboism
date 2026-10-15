package dev.turboism.plugin.modelimport3d.viewer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

public class GlbViewerLoaderFixtureTest {

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

    @Test
    void decodesRealHumanoidFixture() throws Exception {
        final Path fixturePath = resolveFixturePath();
        Assumptions.assumeTrue(fixturePath != null, "TURBOISM_VIEWER_FIXTURE not configured or file not found, skipping real fixture test");

        final byte[] bytes = Files.readAllBytes(fixturePath);
        final long start = System.currentTimeMillis();
        final ViewerScene scene = GlbViewerLoader.load(bytes, () -> false);
        final long elapsed = System.currentTimeMillis() - start;

        assertNotNull(scene);

        // 1. Verify statistics
        final ViewerScene.Stats stats = scene.stats();
        assertEquals(bytes.length, stats.sourceBytes());
        assertEquals(16, stats.meshCount());
        assertEquals(121097, stats.triangleCount());
        assertEquals(15, stats.materialCount());
        assertEquals(10, stats.textureCount());
        assertEquals(2, stats.skinCount());
        assertEquals(144, stats.morphTargetCount());

        // 2. Verify primitives / render meshes
        assertEquals(26, scene.meshes().size());

        // 3. Verify materials
        assertEquals(15, scene.materials().size());

        ViewerScene.Material mat0 = scene.materials().get(0);
        assertEquals("Body", mat0.name());
        assertEquals(0, mat0.baseColorTexture());
        assertEquals(-1, mat0.emissiveTexture());
        assertTrue(mat0.unlit());
        assertEquals("BLEND", mat0.alphaMode());

        ViewerScene.Material mat5 = scene.materials().get(5);
        assertEquals("M_actor_laevat_cloth_01", mat5.name());
        assertEquals(-1, mat5.baseColorTexture());
        assertEquals(2, mat5.emissiveTexture());
        assertFalse(mat5.unlit());
        assertEquals(3.0f, mat5.emissiveFactor()[0], 1e-4f);
        assertEquals(3.0f, mat5.emissiveFactor()[1], 1e-4f);
        assertEquals(3.0f, mat5.emissiveFactor()[2], 1e-4f);

        ViewerScene.Material mat14 = scene.materials().get(14);
        assertEquals("Tongue", mat14.name());
        assertEquals(9, mat14.baseColorTexture());
        assertTrue(mat14.unlit());

        // 4. Verify textures
        assertEquals(10, scene.textures().size());
        for (int i = 0; i < scene.textures().size(); i++) {
            ViewerScene.Texture tex = scene.textures().get(i);
            assertTrue(tex.width() > 0, "Texture " + i + " width <= 0");
            assertTrue(tex.height() > 0, "Texture " + i + " height <= 0");
            assertNotNull(tex.argb(), "Texture " + i + " argb is null");
            assertEquals(tex.width() * tex.height(), tex.argb().length, "Texture " + i + " argb length mismatch");
        }

        // Texture 0 should be 4096x4096
        assertEquals(4096, scene.textures().get(0).width());
        assertEquals(4096, scene.textures().get(0).height());

        // 5. Verify default pose AABB bounds
        ViewerScene.Bounds b = scene.bounds();
        assertEquals(-2.9409928f, b.minX(), 0.05f);
        assertEquals(0.63686574f, b.maxX(), 0.05f);
        assertEquals(-0.30238384f, b.minY(), 0.05f);
        assertEquals(1.7328216f, b.maxY(), 0.05f);
        assertEquals(-0.8568132f, b.minZ(), 0.05f);
        assertEquals(0.40870896f, b.maxZ(), 0.05f);

        // 6. Write summary to local build directory
        final File summaryDir = new File("build/reports/viewer-fixture");
        summaryDir.mkdirs();
        final File summaryFile = new File(summaryDir, "fixture-decode-summary.txt");
        try (FileWriter writer = new FileWriter(summaryFile)) {
            writer.write("GLB Humanoid Fixture Decode Summary\n");
            writer.write("File: " + fixturePath + "\n");
            writer.write("Source Bytes: " + stats.sourceBytes() + "\n");
            writer.write("Load Duration: " + elapsed + " ms\n");
            writer.write("Meshes: " + stats.meshCount() + " (primitives=" + scene.meshes().size() + ")\n");
            writer.write("Triangles: " + stats.triangleCount() + "\n");
            writer.write("Materials: " + stats.materialCount() + "\n");
            writer.write("Textures (Decoded Images): " + stats.textureCount() + "\n");
            writer.write("Skins: " + stats.skinCount() + "\n");
            writer.write("Morph Targets: " + stats.morphTargetCount() + "\n");
            writer.write("AABB Min: [" + b.minX() + ", " + b.minY() + ", " + b.minZ() + "]\n");
            writer.write("AABB Max: [" + b.maxX() + ", " + b.maxY() + ", " + b.maxZ() + "]\n");
            writer.write("Status: SUCCESS\n");
        }
    }

    @Test
    void respectsCancellationDuringFixtureLoad() throws Exception {
        final Path fixturePath = resolveFixturePath();
        Assumptions.assumeTrue(fixturePath != null, "TURBOISM_VIEWER_FIXTURE not configured or file not found, skipping real fixture test");
        final byte[] bytes = Files.readAllBytes(fixturePath);
        assertThrows(CancellationException.class, () -> GlbViewerLoader.load(bytes, () -> true));
    }
}
