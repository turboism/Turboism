package dev.turboism.plugin.modelimport3d.viewer;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class SoftwareModelRendererTest {

    private static final int BG_COLOR = 0xFF2B2D30;

    @Test
    void testRenderBasicScene() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Quad at origin facing +Z (front)
        // CCW vertices when looking from +Z:
        // v0: (-1, -1, 0), v1: (1, -1, 0), v2: (1, 1, 0), v3: (-1, 1, 0)
        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        float[] normals = {
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f
        };
        float[] uvs = {
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        };
        // 2 triangles: (0, 1, 2) and (0, 2, 3)
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, normals, uvs, null, null, indices, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "redMat",
                new float[] {1.0f, 0.0f, 0.0f, 1.0f},
                -1,
                -1,
                new float[] {0f, 0f, 0f},
                "OPAQUE",
                0.5f,
                false,
                true // unlit
                );

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 0, 0, 0);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(mat), List.of(), bounds, stats);

        BufferedImage image = renderer.render(scene, 128, 128, 0.0, 0.0, 1.0);
        assertNotNull(image);
        assertEquals(128, image.getWidth());
        assertEquals(128, image.getHeight());

        int centerPixel = image.getRGB(64, 64);
        int centerRed = (centerPixel >>> 16) & 0xFF;
        int centerGreen = (centerPixel >>> 8) & 0xFF;
        int centerBlue = centerPixel & 0xFF;

        assertEquals(255, centerRed, "Center pixel should be bright red");
        assertEquals(0, centerGreen, "Center pixel green should be 0");
        assertEquals(0, centerBlue, "Center pixel blue should be 0");

        SoftwareModelRenderer.RenderStats rStats = renderer.lastRenderStats();
        assertEquals(2, rStats.totalTriangles());
        assertEquals(2, rStats.renderedTriangles());
        assertEquals(0, rStats.culledTriangles());
    }

    @Test
    void test3DOcclusion() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Quad A: Red at Z = 0.5 (closer to camera)
        float[] posA = {-1f, -1f, 0.5f, 1f, -1f, 0.5f, 1f, 1f, 0.5f, -1f, 1f, 0.5f};
        // Quad B: Blue at Z = -0.5 (further from camera)
        float[] posB = {-1f, -1f, -0.5f, 1f, -1f, -0.5f, 1f, 1f, -0.5f, -1f, 1f, -0.5f};
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh meshA = new ViewerScene.Mesh("quadA", posA, null, null, null, null, indices, 0);
        ViewerScene.Mesh meshB = new ViewerScene.Mesh("quadB", posB, null, null, null, null, indices, 1);

        ViewerScene.Material redMat = new ViewerScene.Material(
                "red", new float[] {1f, 0f, 0f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);
        ViewerScene.Material blueMat = new ViewerScene.Material(
                "blue", new float[] {0f, 0f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, -0.5f, 1f, 1f, 0.5f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 2, 4, 2, 0, 0, 0);

        // Case 1: Submit Red first, then Blue
        ViewerScene scene1 = new ViewerScene(List.of(meshA, meshB), List.of(redMat, blueMat), List.of(), bounds, stats);
        BufferedImage img1 = renderer.render(scene1, 64, 64, 0.0, 0.0, 1.0);
        int px1 = img1.getRGB(32, 32);
        int r1 = (px1 >>> 16) & 0xFF;
        int b1 = px1 & 0xFF;
        assertEquals(255, r1, "Red quad is in front and should occlude blue quad");
        assertEquals(0, b1);

        // Case 2: Submit Blue first, then Red
        ViewerScene scene2 = new ViewerScene(List.of(meshB, meshA), List.of(redMat, blueMat), List.of(), bounds, stats);
        BufferedImage img2 = renderer.render(scene2, 64, 64, 0.0, 0.0, 1.0);
        int px2 = img2.getRGB(32, 32);
        int r2 = (px2 >>> 16) & 0xFF;
        int b2 = px2 & 0xFF;
        assertEquals(255, r2, "Red quad is in front and must still occlude blue quad regardless of submission order");
        assertEquals(0, b2);
    }

    @Test
    void testTextureSampling() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // 2x2 texture:
        // top-left (0,0): Red (0xFFFF0000)
        // top-right (1,0): Green (0xFF00FF00)
        // bottom-left (0,1): Blue (0xFF0000FF)
        // bottom-right (1,1): Yellow (0xFFFFFF00)
        int[] texData = {
            0xFFFF0000, 0xFF00FF00,
            0xFF0000FF, 0xFFFFFF00
        };
        ViewerScene.Texture tex = new ViewerScene.Texture(2, 2, texData);

        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        // UVs: (0,0) is top-left, (1,1) is bottom-right
        // In 3D: v3=(-1, 1) is top-left in screen, v2=(1, 1) is top-right, v0=(-1, -1) is bottom-left, v1=(1, -1) is
        // bottom-right
        float[] uvs = {
            0.0f, 1.0f, // v0 (bottom-left)
            1.0f, 1.0f, // v1 (bottom-right)
            1.0f, 0.0f, // v2 (top-right)
            0.0f, 0.0f // v3 (top-left)
        };
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, null, uvs, null, null, indices, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "texturedMat",
                new float[] {1f, 1f, 1f, 1f},
                0, // baseColorTexture
                -1,
                new float[] {0f, 0f, 0f},
                "OPAQUE",
                0.5f,
                true,
                true // unlit
                );

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 1, 0, 0);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(mat), List.of(tex), bounds, stats);

        BufferedImage img = renderer.render(scene, 128, 128, 0.0, 0.0, 1.0);

        // Check top-left quadrant of quad (screen x: ~40, y: ~40)
        int pxTL = img.getRGB(45, 45);
        assertEquals(255, (pxTL >>> 16) & 0xFF, "Top-left quadrant should sample Red");
        assertEquals(0, (pxTL >>> 8) & 0xFF);

        // Check top-right quadrant (screen x: ~85, y: ~40)
        int pxTR = img.getRGB(85, 45);
        assertEquals(0, (pxTR >>> 16) & 0xFF);
        assertEquals(255, (pxTR >>> 8) & 0xFF, "Top-right quadrant should sample Green");

        // Check bottom-left quadrant (screen x: ~40, y: ~85)
        int pxBL = img.getRGB(45, 85);
        assertEquals(0, (pxBL >>> 16) & 0xFF);
        assertEquals(255, pxBL & 0xFF, "Bottom-left quadrant should sample Blue");

        // Check bottom-right quadrant (screen x: ~85, y: ~85)
        int pxBR = img.getRGB(85, 85);
        assertEquals(255, (pxBR >>> 16) & 0xFF, "Bottom-right quadrant should sample Yellow (Red=255)");
        assertEquals(255, (pxBR >>> 8) & 0xFF, "Bottom-right quadrant should sample Yellow (Green=255)");
    }

    @Test
    void testAlphaModes() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Texture with 2x2 pixels:
        // Left column: alpha = 0.0 (0x00FF0000)
        // Right column: alpha = 1.0 (0xFFFF0000)
        int[] texData = {
            0x00FF0000, 0xFFFF0000,
            0x00FF0000, 0xFFFF0000
        };
        ViewerScene.Texture tex = new ViewerScene.Texture(2, 2, texData);

        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        float[] uvs = {
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        };
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, null, uvs, null, null, indices, 0);

        // 1. MASK mode: left side (alpha=0 < 0.5) must be discarded, showing background!
        ViewerScene.Material maskMat = new ViewerScene.Material(
                "maskMat", new float[] {1f, 1f, 1f, 1f}, 0, -1, new float[] {0f, 0f, 0f}, "MASK", 0.5f, true, true);
        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 1, 0, 0);
        ViewerScene sceneMask = new ViewerScene(List.of(mesh), List.of(maskMat), List.of(tex), bounds, stats);

        BufferedImage maskImg = renderer.render(sceneMask, 128, 128, 0.0, 0.0, 1.0);
        int leftPixelMask = maskImg.getRGB(45, 64);
        int rightPixelMask = maskImg.getRGB(85, 64);

        assertEquals(BG_COLOR, leftPixelMask, "Left side in MASK mode should discard fragment and retain background");
        assertEquals(0xFFFF0000, rightPixelMask, "Right side in MASK mode should render opaque red");

        // 2. OPAQUE mode: left side with alpha=0 is still rendered opaque red!
        ViewerScene.Material opaqueMat = new ViewerScene.Material(
                "opaqueMat", new float[] {1f, 1f, 1f, 1f}, 0, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);
        ViewerScene sceneOpaque = new ViewerScene(List.of(mesh), List.of(opaqueMat), List.of(tex), bounds, stats);
        BufferedImage opaqueImg = renderer.render(sceneOpaque, 128, 128, 0.0, 0.0, 1.0);
        int leftPixelOpaque = opaqueImg.getRGB(45, 64);
        assertEquals(0xFFFF0000, leftPixelOpaque, "OPAQUE mode should ignore texture alpha and render opaque red");

        // 3. BLEND mode: translucent green (A=0.5) quad in front of opaque red quad
        float[] posBg = {-1f, -1f, -0.2f, 1f, -1f, -0.2f, 1f, 1f, -0.2f, -1f, 1f, -0.2f};
        ViewerScene.Mesh meshBg = new ViewerScene.Mesh("bgQuad", posBg, null, null, null, null, indices, 0);
        ViewerScene.Material redOpaque = new ViewerScene.Material(
                "red", new float[] {1f, 0f, 0f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);

        float[] posFg = {-1f, -1f, 0.2f, 1f, -1f, 0.2f, 1f, 1f, 0.2f, -1f, 1f, 0.2f};
        ViewerScene.Mesh meshFg = new ViewerScene.Mesh("fgQuad", posFg, null, null, null, null, indices, 1);
        ViewerScene.Material greenBlend = new ViewerScene.Material(
                "greenBlend",
                new float[] {0f, 1f, 0f, 0.5f},
                -1,
                -1,
                new float[] {0f, 0f, 0f},
                "BLEND",
                0.5f,
                true,
                true);

        ViewerScene sceneBlend =
                new ViewerScene(List.of(meshBg, meshFg), List.of(redOpaque, greenBlend), List.of(), bounds, stats);
        BufferedImage blendImg = renderer.render(sceneBlend, 64, 64, 0.0, 0.0, 1.0);
        int blendPixel = blendImg.getRGB(32, 32);
        int blendR = (blendPixel >>> 16) & 0xFF;
        int blendG = (blendPixel >>> 8) & 0xFF;
        int blendB = blendPixel & 0xFF;

        // Expect ~50% Red and ~50% Green
        assertTrue(blendR > 100 && blendR < 160, "Blended Red should be ~128 but was: " + blendR);
        assertTrue(blendG > 100 && blendG < 160, "Blended Green should be ~128 but was: " + blendG);
        assertEquals(0, blendB);

        // 4. BLEND depth test: transparent quad behind opaque quad must be occluded
        float[] posBehind = {-1f, -1f, -0.8f, 1f, -1f, -0.8f, 1f, 1f, -0.8f, -1f, 1f, -0.8f};
        ViewerScene.Mesh meshBehind = new ViewerScene.Mesh("behindQuad", posBehind, null, null, null, null, indices, 2);
        ViewerScene.Material blueBlend = new ViewerScene.Material(
                "blueBlend",
                new float[] {0f, 0f, 1f, 0.9f},
                -1,
                -1,
                new float[] {0f, 0f, 0f},
                "BLEND",
                0.5f,
                true,
                true);
        ViewerScene sceneOccluded = new ViewerScene(
                List.of(meshBg, meshBehind), List.of(redOpaque, greenBlend, blueBlend), List.of(), bounds, stats);
        BufferedImage occludedImg = renderer.render(sceneOccluded, 64, 64, 0.0, 0.0, 1.0);
        int occPixel = occludedImg.getRGB(32, 32);
        assertEquals(
                255, (occPixel >>> 16) & 0xFF, "Opaque red quad in front must occlude transparent blue quad behind it");
        assertEquals(
                0, occPixel & 0xFF, "Blue component should be 0 because transparent blue quad was behind opaque red");
    }

    @Test
    void testYawPitchRotationPixelVariation() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Create a 3D box centered at origin
        // Front face (+Z): Red
        // Back face (-Z): Blue
        // Side face (+X): Green
        float[] posFront = {-1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f};
        float[] posBack = {1f, -1f, -1f, -1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f};
        float[] posSide = {1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f};
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mFront = new ViewerScene.Mesh("front", posFront, null, null, null, null, indices, 0);
        ViewerScene.Mesh mBack = new ViewerScene.Mesh("back", posBack, null, null, null, null, indices, 1);
        ViewerScene.Mesh mSide = new ViewerScene.Mesh("side", posSide, null, null, null, null, indices, 2);

        ViewerScene.Material matRed = new ViewerScene.Material(
                "red", new float[] {1f, 0f, 0f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, false, true);
        ViewerScene.Material matBlue = new ViewerScene.Material(
                "blue", new float[] {0f, 0f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, false, true);
        ViewerScene.Material matGreen = new ViewerScene.Material(
                "green", new float[] {0f, 1f, 0f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, false, true);

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, -1f, 1f, 1f, 1f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 3, 6, 3, 0, 0, 0);
        ViewerScene scene = new ViewerScene(
                List.of(mFront, mBack, mSide), List.of(matRed, matBlue, matGreen), List.of(), bounds, stats);

        // 1. Front view (yaw = 0): center must be Red
        BufferedImage imgFront = renderer.render(scene, 64, 64, 0.0, 0.0, 1.0);
        int pxFront = imgFront.getRGB(32, 32);
        assertEquals(255, (pxFront >>> 16) & 0xFF, "Front view should be Red");
        assertEquals(0, pxFront & 0xFF);

        // 2. Back view (yaw = PI): center must be Blue
        BufferedImage imgBack = renderer.render(scene, 64, 64, Math.PI, 0.0, 1.0);
        int pxBack = imgBack.getRGB(32, 32);
        assertEquals(0, (pxBack >>> 16) & 0xFF);
        assertEquals(255, pxBack & 0xFF, "Back view should be Blue");

        // 3. Side view (yaw = -PI/2): center must be Green
        BufferedImage imgSide = renderer.render(scene, 64, 64, -Math.PI * 0.5, 0.0, 1.0);
        int pxSide = imgSide.getRGB(32, 32);
        assertEquals(255, (pxSide >>> 8) & 0xFF, "Side view should be Green");
    }

    @Test
    void testZoomScaling() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, null, null, null, null, indices, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1f, 1f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);
        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 0, 0, 0);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(mat), List.of(), bounds, stats);

        BufferedImage img1 = renderer.render(scene, 128, 128, 0.0, 0.0, 0.5);
        BufferedImage img2 = renderer.render(scene, 128, 128, 0.0, 0.0, 1.0);

        int count1 = countNonBackgroundPixels(img1, BG_COLOR);
        int count2 = countNonBackgroundPixels(img2, BG_COLOR);

        assertTrue(
                count2 > count1 * 3,
                "Zooming in 2x should roughly quadruple covered area (was count1=" + count1 + ", count2=" + count2
                        + ")");
    }

    @Test
    void testDoubleSidedAndBackFaceCulling() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Quad facing away from camera (-Z normal, CW winding when viewed from +Z)
        float[] positions = {-1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f, 1f, -1f, 0f};
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, null, null, null, null, indices, 0);

        // Case 1: doubleSided = false -> culled!
        ViewerScene.Material singleSided = new ViewerScene.Material(
                "single", new float[] {1f, 1f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, false, true);
        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 0, 0, 0);
        ViewerScene sceneSingle = new ViewerScene(List.of(mesh), List.of(singleSided), List.of(), bounds, stats);

        BufferedImage imgSingle = renderer.render(sceneSingle, 64, 64, 0.0, 0.0, 1.0);
        assertEquals(
                0,
                countNonBackgroundPixels(imgSingle, BG_COLOR),
                "Back-facing triangle should be culled when doubleSided is false");
        assertEquals(2, renderer.lastRenderStats().culledTriangles());

        // Case 2: doubleSided = true -> rendered!
        ViewerScene.Material doubleSided = new ViewerScene.Material(
                "double", new float[] {1f, 1f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);
        ViewerScene sceneDouble = new ViewerScene(List.of(mesh), List.of(doubleSided), List.of(), bounds, stats);

        BufferedImage imgDouble = renderer.render(sceneDouble, 64, 64, 0.0, 0.0, 1.0);
        assertTrue(
                countNonBackgroundPixels(imgDouble, BG_COLOR) > 0,
                "Back-facing triangle must be rendered when doubleSided is true");
        assertEquals(2, renderer.lastRenderStats().renderedTriangles());
    }

    @Test
    void testEmissiveAndVertexColors() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        // RGBA vertex colors: all Blue
        float[] colors = {
            0f, 0f, 1f, 1f,
            0f, 0f, 1f, 1f,
            0f, 0f, 1f, 1f,
            0f, 0f, 1f, 1f
        };
        int[] indices = {0, 1, 2, 0, 2, 3};

        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, null, null, null, colors, indices, 0);

        // Material with baseColor factor white, emissive factor Red [1, 0, 0]
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat",
                new float[] {1f, 1f, 1f, 1f},
                -1,
                -1,
                new float[] {1f, 0f, 0f}, // Emissive Red
                "OPAQUE",
                0.5f,
                true,
                true // unlit
                );

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 0, 0, 0);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(mat), List.of(), bounds, stats);

        BufferedImage img = renderer.render(scene, 64, 64, 0.0, 0.0, 1.0);
        int px = img.getRGB(32, 32);
        int r = (px >>> 16) & 0xFF;
        int g = (px >>> 8) & 0xFF;
        int b = px & 0xFF;

        assertEquals(255, r, "Emissive factor Red should produce full Red component");
        assertEquals(0, g);
        assertEquals(255, b, "Vertex color Blue should produce full Blue component");
    }

    @Test
    void testInterruptionCancellation() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f};
        int[] indices = {0, 1, 2};
        ViewerScene.Mesh mesh = new ViewerScene.Mesh("tri", positions, null, null, null, null, indices, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "mat", new float[] {1f, 1f, 1f, 1f}, -1, -1, new float[] {0f, 0f, 0f}, "OPAQUE", 0.5f, true, true);
        ViewerScene scene = new ViewerScene(
                List.of(mesh),
                List.of(mat),
                List.of(),
                new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f),
                new ViewerScene.Stats(10, 1, 1, 1, 0, 0, 0));

        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> renderer.render(scene, 64, 64, 0.0, 0.0, 1.0));
        } finally {
            // Clear interrupted status
            Thread.interrupted();
        }
    }

    @Test
    void testShadingModesSourceVsGeometry() {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Quad facing camera (+Z) with normals [0, 0, 1]
        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        float[] normals = {
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f
        };
        int[] indices = {0, 1, 2, 0, 2, 3};

        // Material with saturated red base color, unlit=true, emissive blue
        ViewerScene.Mesh mesh = new ViewerScene.Mesh("quad", positions, normals, null, null, null, indices, 0);
        ViewerScene.Material mat = new ViewerScene.Material(
                "coloredMat",
                new float[] {1.0f, 0.0f, 0.0f, 1.0f}, // red
                -1,
                -1,
                new float[] {0.0f, 0.0f, 1.0f}, // emissive blue
                "OPAQUE",
                0.5f,
                true,
                true // unlit in source material
                );

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1f, -1f, 0f, 1f, 1f, 0f);
        ViewerScene.Stats stats = new ViewerScene.Stats(100, 1, 2, 1, 0, 0, 0);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(mat), List.of(), bounds, stats);

        // 1. Default render (SOURCE_MATERIAL): red + emissive blue
        BufferedImage imgSource = renderer.render(scene, 64, 64, 0.0, 0.0, 1.0);
        int pxSource = imgSource.getRGB(32, 32);
        int rSource = (pxSource >>> 16) & 0xFF;
        int gSource = (pxSource >>> 8) & 0xFF;
        int bSource = pxSource & 0xFF;
        assertEquals(255, rSource, "Source material mode must retain base red");
        assertEquals(0, gSource);
        assertEquals(255, bSource, "Source material mode must retain emissive blue");

        // 2. Explicit SOURCE_MATERIAL overload: must produce exact same result
        BufferedImage imgExplicitSource =
                renderer.render(scene, 64, 64, 0.0, 0.0, 1.0, SoftwareModelRenderer.Shading.SOURCE_MATERIAL);
        assertEquals(pxSource, imgExplicitSource.getRGB(32, 32));

        // 3. GEOMETRY mode: neutral light gray (baseRGB ~0.7), emissive disabled, normal lighting active (unlit forced
        // to false)
        BufferedImage imgGeom = renderer.render(scene, 64, 64, 0.0, 0.0, 1.0, SoftwareModelRenderer.Shading.GEOMETRY);
        int pxGeom = imgGeom.getRGB(32, 32);
        int rGeom = (pxGeom >>> 16) & 0xFF;
        int gGeom = (pxGeom >>> 8) & 0xFF;
        int bGeom = pxGeom & 0xFF;

        // Neutral gray: R, G, and B must be equal
        assertEquals(rGeom, gGeom, "Geometry mode must produce neutral gray (R == G)");
        assertEquals(gGeom, bGeom, "Geometry mode must produce neutral gray (G == B)");
        assertTrue(
                rGeom > 130 && rGeom < 220, "Geometry mode should be lit neutral light gray (~140-200), got: " + rGeom);

        // 4. GEOMETRY mode preserves alpha masking
        int[] texData = {0x00FFFFFF, 0xFFFFFFFF}; // Left alpha=0, Right alpha=1
        ViewerScene.Texture tex = new ViewerScene.Texture(2, 1, texData);
        float[] uvs = {0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f};
        ViewerScene.Mesh maskMesh = new ViewerScene.Mesh("maskQuad", positions, normals, uvs, null, null, indices, 0);
        ViewerScene.Material maskMat = new ViewerScene.Material(
                "maskMat", new float[] {1f, 1f, 1f, 1f}, 0, -1, new float[] {0f, 0f, 0f}, "MASK", 0.5f, true, false);
        ViewerScene maskScene = new ViewerScene(List.of(maskMesh), List.of(maskMat), List.of(tex), bounds, stats);
        BufferedImage imgGeomMask =
                renderer.render(maskScene, 128, 128, 0.0, 0.0, 1.0, SoftwareModelRenderer.Shading.GEOMETRY);

        int leftMaskPx = imgGeomMask.getRGB(45, 64);
        int rightMaskPx = imgGeomMask.getRGB(85, 64);
        assertEquals(BG_COLOR, leftMaskPx, "Geometry mode must preserve MASK cutoff discarding left side");
        assertNotEquals(BG_COLOR, rightMaskPx, "Geometry mode must preserve MASK rendering right side");
        int rRight = (rightMaskPx >>> 16) & 0xFF;
        int gRight = (rightMaskPx >>> 8) & 0xFF;
        int bRight = rightMaskPx & 0xFF;
        assertEquals(rRight, gRight, "Rendered side in Geometry mode must still be neutral gray");
        assertEquals(gRight, bRight);
    }

    @Test
    void testHighPoly121kTrianglesPerformanceAndStats() throws IOException {
        SoftwareModelRenderer renderer = new SoftwareModelRenderer(BG_COLOR);

        // Generate synthetic multi-mesh scene matching Surtr fixture (121,000 triangles)
        int targetTriangles = 121_000;
        int gridCols = 220;
        int gridRows = (targetTriangles / 2) / gridCols; // 275 rows -> 220 * 275 * 2 = 121,000 triangles!
        int actualTriangles = gridCols * gridRows * 2;

        int vertCols = gridCols + 1;
        int vertRows = gridRows + 1;
        int totalVerts = vertCols * vertRows;

        float[] positions = new float[totalVerts * 3];
        float[] normals = new float[totalVerts * 3];
        float[] uvs = new float[totalVerts * 2];

        for (int r = 0; r < vertRows; r++) {
            float v = (float) r / gridRows;
            float phi = (float) (v * Math.PI);
            for (int c = 0; c < vertCols; c++) {
                float u = (float) c / gridCols;
                float theta = (float) (u * 2.0 * Math.PI);

                float x = (float) (Math.sin(phi) * Math.cos(theta));
                float y = (float) Math.cos(phi);
                float z = (float) (Math.sin(phi) * Math.sin(theta));

                int idx = r * vertCols + c;
                positions[3 * idx] = x;
                positions[3 * idx + 1] = y;
                positions[3 * idx + 2] = z;

                normals[3 * idx] = x;
                normals[3 * idx + 1] = y;
                normals[3 * idx + 2] = z;

                uvs[2 * idx] = u;
                uvs[2 * idx + 1] = v;
            }
        }

        int[] indices = new int[actualTriangles * 3];
        int tIdx = 0;
        for (int r = 0; r < gridRows; r++) {
            for (int c = 0; c < gridCols; c++) {
                int i0 = r * vertCols + c;
                int i1 = r * vertCols + (c + 1);
                int i2 = (r + 1) * vertCols + (c + 1);
                int i3 = (r + 1) * vertCols + c;

                // Tri 1: CCW
                indices[tIdx++] = i0;
                indices[tIdx++] = i1;
                indices[tIdx++] = i2;

                // Tri 2: CCW
                indices[tIdx++] = i0;
                indices[tIdx++] = i2;
                indices[tIdx++] = i3;
            }
        }

        // Texture: 128x128 pattern
        int[] texData = new int[128 * 128];
        for (int i = 0; i < texData.length; i++) {
            int tx = i % 128;
            int ty = i / 128;
            texData[i] = ((tx / 16 + ty / 16) % 2 == 0) ? 0xFFE04040 : 0xFF4080E0;
        }
        ViewerScene.Texture texture = new ViewerScene.Texture(128, 128, texData);

        ViewerScene.Mesh mesh =
                new ViewerScene.Mesh("highPolyHumanoidProxy", positions, normals, uvs, null, null, indices, 0);
        ViewerScene.Material material = new ViewerScene.Material(
                "surtrMaterial",
                new float[] {1.0f, 1.0f, 1.0f, 1.0f},
                0,
                -1,
                new float[] {0.0f, 0.0f, 0.0f},
                "OPAQUE",
                0.5f,
                false, // single-sided to exercise backface culling
                false // lit shading
                );

        ViewerScene.Bounds bounds = new ViewerScene.Bounds(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f);
        ViewerScene.Stats sceneStats = new ViewerScene.Stats(82512460L, 1, actualTriangles, 1, 1, 2, 144);
        ViewerScene scene = new ViewerScene(List.of(mesh), List.of(material), List.of(texture), bounds, sceneStats);

        // Warm up JIT
        renderer.render(scene, 320, 320, 0.0, 0.0, 1.0);

        // Benchmark at interactive 640x640 resolution
        SoftwareModelRenderer.RenderResult result = renderer.renderWithStats(scene, 640, 640, 0.4, 0.2, 1.0);

        assertNotNull(result.image());
        assertEquals(640, result.image().getWidth());
        assertEquals(640, result.image().getHeight());

        SoftwareModelRenderer.RenderStats stats = result.stats();
        assertEquals(actualTriangles, stats.totalTriangles());
        assertTrue(stats.renderedTriangles() > 0, "Must have rendered front-facing triangles");
        assertTrue(stats.culledTriangles() > 0, "Must have culled back-facing triangles");
        assertEquals(actualTriangles, stats.renderedTriangles() + stats.culledTriangles());

        double renderTimeMs = stats.renderTimeMs();

        // Write summary report to build/renderer-summary.txt in both cwd and root build
        Path buildDir = Paths.get("build");
        Files.createDirectories(buildDir);
        Path reportFile = buildDir.resolve("renderer-summary.txt");
        String summary = String.format(
                "SoftwareModelRenderer 121k Triangles Benchmark Summary%n"
                        + "--------------------------------------------------%n"
                        + "Resolution:          %dx%d%n"
                        + "Total Triangles:     %d%n"
                        + "Rendered Triangles:  %d%n"
                        + "Culled Triangles:    %d%n"
                        + "Transparent Passes:  %d%n"
                        + "Render Duration:     %.2f ms (%.1f FPS)%n"
                        + "Status:              PASS%n",
                stats.width(),
                stats.height(),
                stats.totalTriangles(),
                stats.renderedTriangles(),
                stats.culledTriangles(),
                stats.transparentTriangles(),
                renderTimeMs,
                renderTimeMs > 0 ? (1000.0 / renderTimeMs) : 0.0);
        Files.writeString(reportFile, summary, StandardCharsets.UTF_8);

        // Also write to root project build/ if accessible
        try {
            Path rootBuild = Paths.get("../../build");
            if (Files.isDirectory(rootBuild.getParent())) {
                Files.createDirectories(rootBuild);
                Files.writeString(rootBuild.resolve("renderer-summary.txt"), summary, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }

        System.out.println(summary);
    }

    private static int countNonBackgroundPixels(BufferedImage image, int bgColor) {
        int count = 0;
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (image.getRGB(x, y) != bgColor) {
                    count++;
                }
            }
        }
        return count;
    }
}
