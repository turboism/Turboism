package dev.turboism.plugin.modelimport3d.viewer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Pure JDK CPU triangle renderer that rasterizes a {@link ViewerScene} into a {@link BufferedImage}.
 *
 * Circumvents native 3D library and Proton dependencies. Supports camera centering on AABB,
 * yaw/pitch rotation, zoom, depth buffering (Z-buffer), texture sampling with UVs, vertex RGBA
 * colors, emissive maps, alpha modes (OPAQUE, MASK, BLEND), double-sided geometry, and unlit shading.
 */
public final class SoftwareModelRenderer {

    public static final int DEFAULT_BACKGROUND_COLOR = 0xFF2B2D30;

    private static final ViewerScene.Material DEFAULT_MATERIAL = new ViewerScene.Material(
            "default",
            new float[] {1.0f, 1.0f, 1.0f, 1.0f},
            -1,
            -1,
            new float[] {0.0f, 0.0f, 0.0f},
            "OPAQUE",
            0.5f,
            true,
            false);

    public record RenderStats(
            int totalTriangles,
            int renderedTriangles,
            int culledTriangles,
            int transparentTriangles,
            long renderTimeNanos,
            int width,
            int height) {
        public double renderTimeMs() {
            return renderTimeNanos / 1_000_000.0;
        }
    }

    public record RenderResult(BufferedImage image, RenderStats stats) {}

    private final int backgroundColor;
    private final ThreadLocal<WorkerBuffers> workerBuffers = ThreadLocal.withInitial(WorkerBuffers::new);
    private volatile RenderStats lastStats = new RenderStats(0, 0, 0, 0, 0, 0, 0);

    public SoftwareModelRenderer() {
        this(DEFAULT_BACKGROUND_COLOR);
    }

    public SoftwareModelRenderer(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    /**
     * Primary contract method: renders the given scene into a BufferedImage.
     */
    public BufferedImage render(
            ViewerScene scene, int width, int height, double yawRadians, double pitchRadians, double zoom) {
        return renderWithStats(scene, width, height, yawRadians, pitchRadians, zoom)
                .image();
    }

    public RenderStats lastRenderStats() {
        return lastStats;
    }

    /**
     * Renders scene and returns both the image and observable render statistics.
     */
    public RenderResult renderWithStats(
            ViewerScene scene, int width, int height, double yawRadians, double pitchRadians, double zoom) {
        checkInterrupted();
        long startTime = System.nanoTime();

        int targetWidth = Math.max(1, width);
        int targetHeight = Math.max(1, height);

        BufferedImage image = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, backgroundColor);

        if (scene == null || scene.meshes() == null || scene.meshes().isEmpty()) {
            RenderStats stats = new RenderStats(0, 0, 0, 0, System.nanoTime() - startTime, targetWidth, targetHeight);
            this.lastStats = stats;
            return new RenderResult(image, stats);
        }

        // 1. Determine bounding box and camera frame
        BoundingBox bbox = resolveBounds(scene);
        float cx = bbox.cx();
        float cy = bbox.cy();
        float cz = bbox.cz();
        float maxDim = bbox.maxDim();

        // 2. Camera setup
        float distance = maxDim * 2.5f;
        double safeZoom = Math.max(0.001, Math.min(100.0, zoom));
        float minScreen = Math.min(targetWidth, targetHeight);
        float scale = (float) ((minScreen * 0.85 * safeZoom / maxDim) * distance);
        float halfW = targetWidth * 0.5f;
        float halfH = targetHeight * 0.5f;

        float cosY = (float) Math.cos(yawRadians);
        float sinY = (float) Math.sin(yawRadians);
        float cosP = (float) Math.cos(pitchRadians);
        float sinP = (float) Math.sin(pitchRadians);

        // Light direction in view space (from model toward key light: front-top-right)
        float lx = 0.19803f;
        float ly = 0.39606f;
        float lz = 0.89113f;

        // 3. Count total vertices and prepare worker buffers
        List<ViewerScene.Mesh> meshes = scene.meshes();
        int meshCount = meshes.size();
        int[] vertexOffsets = new int[meshCount + 1];
        int totalVertices = 0;
        int totalSceneTriangles = 0;

        for (int m = 0; m < meshCount; m++) {
            ViewerScene.Mesh mesh = meshes.get(m);
            vertexOffsets[m] = totalVertices;
            int vCount = (mesh != null && mesh.positions() != null) ? mesh.positions().length / 3 : 0;
            totalVertices += vCount;

            if (mesh != null) {
                int[] indices = mesh.indices();
                int triCount = (indices != null && indices.length >= 3) ? indices.length / 3 : vCount / 3;
                totalSceneTriangles += triCount;
            }
        }
        vertexOffsets[meshCount] = totalVertices;

        WorkerBuffers buffers = workerBuffers.get();
        buffers.ensureCapacity(targetWidth * targetHeight, totalVertices);
        Arrays.fill(buffers.zBuffer, 0, targetWidth * targetHeight, Float.POSITIVE_INFINITY);
        buffers.transparentTriangles.clear();

        // 4. Transform all vertices into view & screen space
        for (int m = 0; m < meshCount; m++) {
            checkInterrupted();
            ViewerScene.Mesh mesh = meshes.get(m);
            if (mesh == null || mesh.positions() == null) continue;

            float[] pos = mesh.positions();
            int vCount = pos.length / 3;
            int vOffset = vertexOffsets[m];

            float[] normals = mesh.normals();
            boolean hasNormals = normals != null && normals.length >= pos.length;

            ViewerScene.Material mat = resolveMaterial(scene, mesh.materialIndex());
            boolean unlit = mat.unlit();
            boolean doubleSided = mat.doubleSided();

            for (int i = 0; i < vCount; i++) {
                int globalIdx = vOffset + i;
                int p3 = 3 * i;
                float px = pos[p3] - cx;
                float py = pos[p3 + 1] - cy;
                float pz = pos[p3 + 2] - cz;

                // Yaw rotation around Y:
                float rx = px * cosY + pz * sinY;
                float ry = py;
                float rz = -px * sinY + pz * cosY;

                // Pitch rotation around X:
                float x2 = rx;
                float y2 = ry * cosP - rz * sinP;
                float z2 = ry * sinP + rz * cosP;

                float zv = distance - z2;
                if (zv < 0.001f) zv = 0.001f;
                float invZ = 1.0f / zv;

                buffers.projX[globalIdx] = x2 * invZ * scale + halfW;
                buffers.projY[globalIdx] = -y2 * invZ * scale + halfH;
                buffers.projZ[globalIdx] = zv;
                buffers.projInvZ[globalIdx] = invZ;

                if (unlit) {
                    buffers.projLight[globalIdx] = 1.0f;
                } else if (hasNormals) {
                    float nx = normals[p3];
                    float ny = normals[p3 + 1];
                    float nz = normals[p3 + 2];

                    float nrx = nx * cosY + nz * sinY;
                    float nry = ny;
                    float nrz = -nx * sinY + nz * cosY;

                    float nx2 = nrx;
                    float ny2 = nry * cosP - nrz * sinP;
                    float nz2 = nry * sinP + nrz * cosP;

                    float dot = nx2 * lx + ny2 * ly + nz2 * lz;
                    if (doubleSided) dot = Math.abs(dot);
                    else if (dot < 0f) dot = 0f;

                    buffers.projLight[globalIdx] = 0.35f + 0.65f * dot;
                } else {
                    buffers.projLight[globalIdx] = 1.0f;
                }
            }
        }

        int renderedTriangles = 0;
        int culledTriangles = 0;

        // 5. Pass 1: OPAQUE and MASK materials
        for (int m = 0; m < meshCount; m++) {
            ViewerScene.Mesh mesh = meshes.get(m);
            if (mesh == null || mesh.positions() == null) continue;

            ViewerScene.Material mat = resolveMaterial(scene, mesh.materialIndex());
            boolean isBlend = "BLEND".equalsIgnoreCase(mat.alphaMode());

            int vOffset = vertexOffsets[m];
            int vCount = mesh.positions().length / 3;
            int[] indices = mesh.indices();
            int triCount = (indices != null && indices.length >= 3) ? indices.length / 3 : vCount / 3;

            boolean doubleSided = mat.doubleSided();

            for (int t = 0; t < triCount; t++) {
                if ((t & 0x7F) == 0) {
                    checkInterrupted();
                }
                int localI0, localI1, localI2;
                if (indices != null && indices.length >= 3) {
                    localI0 = indices[3 * t];
                    localI1 = indices[3 * t + 1];
                    localI2 = indices[3 * t + 2];
                } else {
                    localI0 = 3 * t;
                    localI1 = 3 * t + 1;
                    localI2 = 3 * t + 2;
                }

                if (localI0 < 0
                        || localI0 >= vCount
                        || localI1 < 0
                        || localI1 >= vCount
                        || localI2 < 0
                        || localI2 >= vCount) {
                    culledTriangles++;
                    continue;
                }

                int g0 = vOffset + localI0;
                int g1 = vOffset + localI1;
                int g2 = vOffset + localI2;

                float x0 = buffers.projX[g0], y0 = buffers.projY[g0];
                float x1 = buffers.projX[g1], y1 = buffers.projY[g1];
                float x2 = buffers.projX[g2], y2 = buffers.projY[g2];

                float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);

                if (!doubleSided) {
                    if (cross >= -1e-5f) {
                        culledTriangles++;
                        continue;
                    }
                } else {
                    if (Math.abs(cross) < 1e-5f) {
                        culledTriangles++;
                        continue;
                    }
                }

                float minTriX = Math.min(x0, Math.min(x1, x2));
                float maxTriX = Math.max(x0, Math.max(x1, x2));
                float minTriY = Math.min(y0, Math.min(y1, y2));
                float maxTriY = Math.max(y0, Math.max(y1, y2));

                if (maxTriX < 0f || minTriX >= targetWidth || maxTriY < 0f || minTriY >= targetHeight) {
                    culledTriangles++;
                    continue;
                }

                // If cross > 0, swap vertex 1 and 2 to normalize winding
                int f0 = g0, f1 = g1, f2 = g2;
                int lf0 = localI0, lf1 = localI1, lf2 = localI2;
                if (cross > 0f) {
                    f1 = g2;
                    f2 = g1;
                    lf1 = localI2;
                    lf2 = localI1;
                    cross = -cross;
                }

                if (isBlend) {
                    float zCentroid = (buffers.projZ[f0] + buffers.projZ[f1] + buffers.projZ[f2]) / 3.0f;
                    buffers.transparentTriangles.add(new TransparentTriangle(m, f0, f1, f2, lf0, lf1, lf2, zCentroid));
                    continue;
                }

                rasterizeTriangle(
                        scene,
                        mesh,
                        mat,
                        f0,
                        f1,
                        f2,
                        lf0,
                        lf1,
                        lf2,
                        cross,
                        minTriX,
                        maxTriX,
                        minTriY,
                        maxTriY,
                        targetWidth,
                        targetHeight,
                        buffers,
                        pixels,
                        false);
                renderedTriangles++;
            }
        }

        // 6. Pass 2: Transparent BLEND materials sorted back-to-front
        int transparentCount = buffers.transparentTriangles.size();
        if (transparentCount > 0) {
            buffers.transparentTriangles.sort(
                    Comparator.comparingDouble(TransparentTriangle::depth).reversed());

            for (int i = 0; i < transparentCount; i++) {
                if ((i & 0x7F) == 0) {
                    checkInterrupted();
                }
                TransparentTriangle tri = buffers.transparentTriangles.get(i);
                ViewerScene.Mesh mesh = meshes.get(tri.meshIndex());
                ViewerScene.Material mat = resolveMaterial(scene, mesh.materialIndex());

                float x0 = buffers.projX[tri.g0()], y0 = buffers.projY[tri.g0()];
                float x1 = buffers.projX[tri.g1()], y1 = buffers.projY[tri.g1()];
                float x2 = buffers.projX[tri.g2()], y2 = buffers.projY[tri.g2()];

                float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);

                float minTriX = Math.min(x0, Math.min(x1, x2));
                float maxTriX = Math.max(x0, Math.max(x1, x2));
                float minTriY = Math.min(y0, Math.min(y1, y2));
                float maxTriY = Math.max(y0, Math.max(y1, y2));

                rasterizeTriangle(
                        scene,
                        mesh,
                        mat,
                        tri.g0(),
                        tri.g1(),
                        tri.g2(),
                        tri.l0(),
                        tri.l1(),
                        tri.l2(),
                        cross,
                        minTriX,
                        maxTriX,
                        minTriY,
                        maxTriY,
                        targetWidth,
                        targetHeight,
                        buffers,
                        pixels,
                        true);
                renderedTriangles++;
            }
        }

        long duration = System.nanoTime() - startTime;
        RenderStats stats = new RenderStats(
                totalSceneTriangles,
                renderedTriangles,
                culledTriangles,
                transparentCount,
                duration,
                targetWidth,
                targetHeight);
        this.lastStats = stats;
        return new RenderResult(image, stats);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Render interrupted");
        }
    }

    private static void rasterizeTriangle(
            ViewerScene scene,
            ViewerScene.Mesh mesh,
            ViewerScene.Material material,
            int g0,
            int g1,
            int g2,
            int l0,
            int l1,
            int l2,
            float cross,
            float minTriX,
            float maxTriX,
            float minTriY,
            float maxTriY,
            int width,
            int height,
            WorkerBuffers buffers,
            int[] pixels,
            boolean isBlendPass) {
        float x0 = buffers.projX[g0], y0 = buffers.projY[g0];
        float x1 = buffers.projX[g1], y1 = buffers.projY[g1];
        float x2 = buffers.projX[g2], y2 = buffers.projY[g2];

        float invDet = 1.0f / cross;
        float dx10 = x1 - x0, dy10 = y1 - y0;
        float dx20 = x2 - x0, dy20 = y2 - y0;

        float invZ0 = buffers.projInvZ[g0];
        float invZ1 = buffers.projInvZ[g1];
        float invZ2 = buffers.projInvZ[g2];

        float dInvZ_dx = ((invZ1 - invZ0) * dy20 - (invZ2 - invZ0) * dy10) * invDet;
        float dInvZ_dy = ((invZ2 - invZ0) * dx10 - (invZ1 - invZ0) * dx20) * invDet;

        // Texture and UVs
        float[] uvs = mesh.uvs();
        boolean hasUvs = uvs != null && uvs.length >= 2 * (Math.max(l0, Math.max(l1, l2)) + 1);
        float u0 = hasUvs ? uvs[2 * l0] : 0f;
        float v0 = hasUvs ? uvs[2 * l0 + 1] : 0f;
        float u1 = hasUvs ? uvs[2 * l1] : 0f;
        float v1 = hasUvs ? uvs[2 * l1 + 1] : 0f;
        float u2 = hasUvs ? uvs[2 * l2] : 0f;
        float v2 = hasUvs ? uvs[2 * l2 + 1] : 0f;

        float uInvZ0 = u0 * invZ0, uInvZ1 = u1 * invZ1, uInvZ2 = u2 * invZ2;
        float vInvZ0 = v0 * invZ0, vInvZ1 = v1 * invZ1, vInvZ2 = v2 * invZ2;

        float dUInvZ_dx = ((uInvZ1 - uInvZ0) * dy20 - (uInvZ2 - uInvZ0) * dy10) * invDet;
        float dUInvZ_dy = ((uInvZ2 - uInvZ0) * dx10 - (uInvZ1 - uInvZ0) * dx20) * invDet;

        float dVInvZ_dx = ((vInvZ1 - vInvZ0) * dy20 - (vInvZ2 - vInvZ0) * dy10) * invDet;
        float dVInvZ_dy = ((vInvZ2 - vInvZ0) * dx10 - (vInvZ1 - vInvZ0) * dx20) * invDet;

        // Emissive UVs
        ViewerScene.Texture emissiveTex = resolveTexture(scene, material.emissiveTexture());
        float[] emissiveUvs = mesh.emissiveUvs();
        boolean hasEmissiveUvs = emissiveUvs != null && emissiveUvs.length >= 2 * (Math.max(l0, Math.max(l1, l2)) + 1);

        float eu0 = hasEmissiveUvs ? emissiveUvs[2 * l0] : u0;
        float ev0 = hasEmissiveUvs ? emissiveUvs[2 * l0 + 1] : v0;
        float eu1 = hasEmissiveUvs ? emissiveUvs[2 * l1] : u1;
        float ev1 = hasEmissiveUvs ? emissiveUvs[2 * l1 + 1] : v1;
        float eu2 = hasEmissiveUvs ? emissiveUvs[2 * l2] : u2;
        float ev2 = hasEmissiveUvs ? emissiveUvs[2 * l2 + 1] : v2;

        float euInvZ0 = eu0 * invZ0, euInvZ1 = eu1 * invZ1, euInvZ2 = eu2 * invZ2;
        float evInvZ0 = ev0 * invZ0, evInvZ1 = ev1 * invZ1, evInvZ2 = ev2 * invZ2;

        float dEUInvZ_dx = ((euInvZ1 - euInvZ0) * dy20 - (euInvZ2 - euInvZ0) * dy10) * invDet;
        float dEUInvZ_dy = ((euInvZ2 - euInvZ0) * dx10 - (euInvZ1 - euInvZ0) * dx20) * invDet;

        float dEVInvZ_dx = ((evInvZ1 - evInvZ0) * dy20 - (evInvZ2 - evInvZ0) * dy10) * invDet;
        float dEVInvZ_dy = ((evInvZ2 - evInvZ0) * dx10 - (evInvZ1 - evInvZ0) * dx20) * invDet;

        // Lighting interpolation
        float lLight0 = buffers.projLight[g0] * invZ0;
        float lLight1 = buffers.projLight[g1] * invZ1;
        float lLight2 = buffers.projLight[g2] * invZ2;

        float dLight_dx = ((lLight1 - lLight0) * dy20 - (lLight2 - lLight0) * dy10) * invDet;
        float dLight_dy = ((lLight2 - lLight0) * dx10 - (lLight1 - lLight0) * dx20) * invDet;

        // Vertex colors (RGBA)
        float[] colors = mesh.colors();
        boolean hasColors = colors != null && colors.length >= 4 * (Math.max(l0, Math.max(l1, l2)) + 1);

        float cr0 = hasColors ? colors[4 * l0] * invZ0 : invZ0;
        float cg0 = hasColors ? colors[4 * l0 + 1] * invZ0 : invZ0;
        float cb0 = hasColors ? colors[4 * l0 + 2] * invZ0 : invZ0;
        float ca0 = hasColors ? colors[4 * l0 + 3] * invZ0 : invZ0;

        float cr1 = hasColors ? colors[4 * l1] * invZ1 : invZ1;
        float cg1 = hasColors ? colors[4 * l1 + 1] * invZ1 : invZ1;
        float cb1 = hasColors ? colors[4 * l1 + 2] * invZ1 : invZ1;
        float ca1 = hasColors ? colors[4 * l1 + 3] * invZ1 : invZ1;

        float cr2 = hasColors ? colors[4 * l2] * invZ2 : invZ2;
        float cg2 = hasColors ? colors[4 * l2 + 1] * invZ2 : invZ2;
        float cb2 = hasColors ? colors[4 * l2 + 2] * invZ2 : invZ2;
        float ca2 = hasColors ? colors[4 * l2 + 3] * invZ2 : invZ2;

        float dCR_dx = ((cr1 - cr0) * dy20 - (cr2 - cr0) * dy10) * invDet;
        float dCR_dy = ((cr2 - cr0) * dx10 - (cr1 - cr0) * dx20) * invDet;

        float dCG_dx = ((cg1 - cg0) * dy20 - (cg2 - cg0) * dy10) * invDet;
        float dCG_dy = ((cg2 - cg0) * dx10 - (cg1 - cg0) * dx20) * invDet;

        float dCB_dx = ((cb1 - cb0) * dy20 - (cb2 - cb0) * dy10) * invDet;
        float dCB_dy = ((cb2 - cb0) * dx10 - (cb1 - cb0) * dx20) * invDet;

        float dCA_dx = ((ca1 - ca0) * dy20 - (ca2 - ca0) * dy10) * invDet;
        float dCA_dy = ((ca2 - ca0) * dx10 - (ca1 - ca0) * dx20) * invDet;

        // Material factors
        float[] bf = material.baseColorFactor();
        float bfR = (bf != null && bf.length > 0) ? bf[0] : 1.0f;
        float bfG = (bf != null && bf.length > 1) ? bf[1] : 1.0f;
        float bfB = (bf != null && bf.length > 2) ? bf[2] : 1.0f;
        float bfA = (bf != null && bf.length > 3) ? bf[3] : 1.0f;

        float[] ef = material.emissiveFactor();
        float efR = (ef != null && ef.length > 0) ? ef[0] : 0.0f;
        float efG = (ef != null && ef.length > 1) ? ef[1] : 0.0f;
        float efB = (ef != null && ef.length > 2) ? ef[2] : 0.0f;
        boolean hasEmissive = emissiveTex != null || efR > 0f || efG > 0f || efB > 0f;

        ViewerScene.Texture baseTex = resolveTexture(scene, material.baseColorTexture());

        boolean isMask = "MASK".equalsIgnoreCase(material.alphaMode());
        float alphaCutoff = material.alphaCutoff() > 0f ? material.alphaCutoff() : 0.5f;

        int startY = Math.max(0, (int) Math.ceil(minTriY - 0.5f));
        int endY = Math.min(height - 1, (int) Math.floor(maxTriY - 0.5f));

        float[] zBuffer = buffers.zBuffer;

        for (int y = startY; y <= endY; y++) {
            float yMid = y + 0.5f;

            float xLeft = Float.POSITIVE_INFINITY;
            float xRight = Float.NEGATIVE_INFINITY;

            // Edge 01
            if ((y0 <= yMid && y1 > yMid) || (y1 <= yMid && y0 > yMid)) {
                float xInt = x0 + (yMid - y0) * (x1 - x0) / (y1 - y0);
                if (xInt < xLeft) xLeft = xInt;
                if (xInt > xRight) xRight = xInt;
            }
            // Edge 12
            if ((y1 <= yMid && y2 > yMid) || (y2 <= yMid && y1 > yMid)) {
                float xInt = x1 + (yMid - y1) * (x2 - x1) / (y2 - y1);
                if (xInt < xLeft) xLeft = xInt;
                if (xInt > xRight) xRight = xInt;
            }
            // Edge 20
            if ((y2 <= yMid && y0 > yMid) || (y0 <= yMid && y2 > yMid)) {
                float xInt = x2 + (yMid - y2) * (x0 - x2) / (y0 - y2);
                if (xInt < xLeft) xLeft = xInt;
                if (xInt > xRight) xRight = xInt;
            }

            if (xLeft > xRight) continue;

            int startX = Math.max(0, (int) Math.ceil(xLeft - 0.5f));
            int endX = Math.min(width - 1, (int) Math.floor(xRight - 0.5f));
            if (startX > endX) continue;

            float xMid = startX + 0.5f;
            float dx = xMid - x0;
            float dy = yMid - y0;

            float curInvZ = invZ0 + dInvZ_dx * dx + dInvZ_dy * dy;
            float curUInvZ = uInvZ0 + dUInvZ_dx * dx + dUInvZ_dy * dy;
            float curVInvZ = vInvZ0 + dVInvZ_dx * dx + dVInvZ_dy * dy;
            float curEUInvZ = euInvZ0 + dEUInvZ_dx * dx + dEUInvZ_dy * dy;
            float curEVInvZ = evInvZ0 + dEVInvZ_dx * dx + dEVInvZ_dy * dy;
            float curLight = lLight0 + dLight_dx * dx + dLight_dy * dy;

            float curCR = cr0 + dCR_dx * dx + dCR_dy * dy;
            float curCG = cg0 + dCG_dx * dx + dCG_dy * dy;
            float curCB = cb0 + dCB_dx * dx + dCB_dy * dy;
            float curCA = ca0 + dCA_dx * dx + dCA_dy * dy;

            int rowOffset = y * width;
            int pixelIdx = rowOffset + startX;

            for (int x = startX; x <= endX; x++, pixelIdx++) {
                if (curInvZ > 0.0f) {
                    float z = 1.0f / curInvZ;
                    if (z < zBuffer[pixelIdx]) {
                        float u = curUInvZ * z;
                        float v = curVInvZ * z;
                        float light = curLight * z;
                        if (light < 0f) light = 0f;
                        else if (light > 1.5f) light = 1.5f;

                        float vertR = hasColors ? Math.max(0f, Math.min(1f, curCR * z)) : 1.0f;
                        float vertG = hasColors ? Math.max(0f, Math.min(1f, curCG * z)) : 1.0f;
                        float vertB = hasColors ? Math.max(0f, Math.min(1f, curCB * z)) : 1.0f;
                        float vertA = hasColors ? Math.max(0f, Math.min(1f, curCA * z)) : 1.0f;

                        float texR = 1.0f, texG = 1.0f, texB = 1.0f, texA = 1.0f;
                        if (baseTex != null) {
                            int texPixel = sampleTexture(baseTex, u, v);
                            texA = ((texPixel >>> 24) & 0xFF) * (1.0f / 255.0f);
                            texR = ((texPixel >>> 16) & 0xFF) * (1.0f / 255.0f);
                            texG = ((texPixel >>> 8) & 0xFF) * (1.0f / 255.0f);
                            texB = (texPixel & 0xFF) * (1.0f / 255.0f);
                        }

                        float baseA = texA * bfA * vertA;

                        if (isMask && !isBlendPass) {
                            if (baseA < alphaCutoff) {
                                curInvZ += dInvZ_dx;
                                curUInvZ += dUInvZ_dx;
                                curVInvZ += dVInvZ_dx;
                                curEUInvZ += dEUInvZ_dx;
                                curEVInvZ += dEVInvZ_dx;
                                curLight += dLight_dx;
                                curCR += dCR_dx;
                                curCG += dCG_dx;
                                curCB += dCB_dx;
                                curCA += dCA_dx;
                                continue;
                            }
                        }

                        float baseR = texR * bfR * vertR;
                        float baseG = texG * bfG * vertG;
                        float baseB = texB * bfB * vertB;

                        float emR = 0f, emG = 0f, emB = 0f;
                        if (hasEmissive) {
                            if (emissiveTex != null) {
                                float eu = curEUInvZ * z;
                                float ev = curEVInvZ * z;
                                int emPixel = sampleTexture(emissiveTex, eu, ev);
                                emR = efR * (((emPixel >>> 16) & 0xFF) * (1.0f / 255.0f));
                                emG = efG * (((emPixel >>> 8) & 0xFF) * (1.0f / 255.0f));
                                emB = efB * ((emPixel & 0xFF) * (1.0f / 255.0f));
                            } else {
                                emR = efR;
                                emG = efG;
                                emB = efB;
                            }
                        }

                        float litR = baseR * light + emR;
                        float litG = baseG * light + emG;
                        float litB = baseB * light + emB;

                        int ir = (int) (litR * 255.0f + 0.5f);
                        int ig = (int) (litG * 255.0f + 0.5f);
                        int ib = (int) (litB * 255.0f + 0.5f);
                        if (ir > 255) ir = 255;
                        else if (ir < 0) ir = 0;
                        if (ig > 255) ig = 255;
                        else if (ig < 0) ig = 0;
                        if (ib > 255) ib = 255;
                        else if (ib < 0) ib = 0;

                        if (isBlendPass) {
                            if (baseA > 0.001f) {
                                float alpha = Math.min(1.0f, baseA);
                                int dst = pixels[pixelIdx];
                                int dstR = (dst >>> 16) & 0xFF;
                                int dstG = (dst >>> 8) & 0xFF;
                                int dstB = dst & 0xFF;

                                int outR = (int) (ir * alpha + dstR * (1.0f - alpha) + 0.5f);
                                int outG = (int) (ig * alpha + dstG * (1.0f - alpha) + 0.5f);
                                int outB = (int) (ib * alpha + dstB * (1.0f - alpha) + 0.5f);
                                if (outR > 255) outR = 255;
                                if (outG > 255) outG = 255;
                                if (outB > 255) outB = 255;

                                pixels[pixelIdx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
                            }
                        } else {
                            zBuffer[pixelIdx] = z;
                            pixels[pixelIdx] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
                        }
                    }
                }

                curInvZ += dInvZ_dx;
                curUInvZ += dUInvZ_dx;
                curVInvZ += dVInvZ_dx;
                curEUInvZ += dEUInvZ_dx;
                curEVInvZ += dEVInvZ_dx;
                curLight += dLight_dx;
                curCR += dCR_dx;
                curCG += dCG_dx;
                curCB += dCB_dx;
                curCA += dCA_dx;
            }
        }
    }

    private static int sampleTexture(ViewerScene.Texture texture, float u, float v) {
        int w = texture.width();
        int h = texture.height();
        int[] argb = texture.argb();

        float uWrap = u >= 0f && u < 1f ? u : u - (float) Math.floor(u);
        float vWrap = v >= 0f && v < 1f ? v : v - (float) Math.floor(v);

        int tx = (int) (uWrap * w);
        int ty = (int) (vWrap * h);
        if (tx < 0) tx = 0;
        else if (tx >= w) tx = w - 1;
        if (ty < 0) ty = 0;
        else if (ty >= h) ty = h - 1;

        return argb[ty * w + tx];
    }

    private static ViewerScene.Material resolveMaterial(ViewerScene scene, int materialIndex) {
        if (scene != null
                && scene.materials() != null
                && materialIndex >= 0
                && materialIndex < scene.materials().size()) {
            ViewerScene.Material m = scene.materials().get(materialIndex);
            if (m != null) return m;
        }
        return DEFAULT_MATERIAL;
    }

    private static ViewerScene.Texture resolveTexture(ViewerScene scene, int textureIndex) {
        if (scene != null
                && scene.textures() != null
                && textureIndex >= 0
                && textureIndex < scene.textures().size()) {
            ViewerScene.Texture t = scene.textures().get(textureIndex);
            if (t != null && t.width() > 0 && t.height() > 0 && t.argb() != null) {
                return t;
            }
        }
        return null;
    }

    private static BoundingBox resolveBounds(ViewerScene scene) {
        ViewerScene.Bounds b = scene.bounds();
        if (b != null
                && b.minX() <= b.maxX()
                && b.minY() <= b.maxY()
                && b.minZ() <= b.maxZ()
                && (b.minX() != 0f
                        || b.maxX() != 0f
                        || b.minY() != 0f
                        || b.maxY() != 0f
                        || b.minZ() != 0f
                        || b.maxZ() != 0f)) {
            float cx = (b.minX() + b.maxX()) * 0.5f;
            float cy = (b.minY() + b.maxY()) * 0.5f;
            float cz = (b.minZ() + b.maxZ()) * 0.5f;
            float maxDim = Math.max(b.maxX() - b.minX(), Math.max(b.maxY() - b.minY(), b.maxZ() - b.minZ()));
            return new BoundingBox(cx, cy, cz, maxDim <= 1e-5f ? 1.0f : maxDim);
        }

        // Compute from mesh positions
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        if (scene.meshes() != null) {
            for (ViewerScene.Mesh m : scene.meshes()) {
                if (m == null || m.positions() == null) continue;
                float[] p = m.positions();
                int count = p.length / 3;
                for (int i = 0; i < count; i++) {
                    float x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    if (z < minZ) minZ = z;
                    if (z > maxZ) maxZ = z;
                }
            }
        }

        if (Float.isInfinite(minX)) {
            return new BoundingBox(0f, 0f, 0f, 1.0f);
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        return new BoundingBox(cx, cy, cz, maxDim <= 1e-5f ? 1.0f : maxDim);
    }

    private record BoundingBox(float cx, float cy, float cz, float maxDim) {}

    private record TransparentTriangle(int meshIndex, int g0, int g1, int g2, int l0, int l1, int l2, float depth) {}

    private static final class WorkerBuffers {
        float[] zBuffer = new float[0];
        float[] projX = new float[0];
        float[] projY = new float[0];
        float[] projZ = new float[0];
        float[] projInvZ = new float[0];
        float[] projLight = new float[0];
        final List<TransparentTriangle> transparentTriangles = new ArrayList<>();

        void ensureCapacity(int pixelCount, int vertexCount) {
            if (zBuffer.length < pixelCount) {
                zBuffer = new float[pixelCount];
            }
            if (projX.length < vertexCount) {
                projX = new float[vertexCount];
                projY = new float[vertexCount];
                projZ = new float[vertexCount];
                projInvZ = new float[vertexCount];
                projLight = new float[vertexCount];
            }
        }
    }
}
