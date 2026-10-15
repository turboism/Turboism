package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/**
 * Prepares native Cubism model image materials from a {@link ViewerScene}.
 *
 * <p>Converts real glTF materials into deduplicated RGBA PNG textures suitable for registration
 * as Cubism {@link AuthorizedImageContent}, normalizes the scene (promoting emissive UVs to primary UVs
 * when meshes lack a baseColorTexture), correctly handles sRGB/linear blending for base and emissive textures,
 * supports varying texture resolutions, respects alpha modes (OPAQUE, MASK, BLEND), and generates
 * bounded constant RGBA textures for untextured or constant materials to ensure valid Cubism bindings.</p>
 */
public record NativeMaterialPlan(ViewerScene scene, List<Integer> meshImageIndices, List<Image> images) {

    public NativeMaterialPlan {
        Objects.requireNonNull(scene, "scene cannot be null");
        meshImageIndices = List.copyOf(meshImageIndices);
        images = List.copyOf(images);
    }

    /**
     * Bounded, deduplicated PNG model image deliverable.
     */
    public record Image(int width, int height, byte[] png, String contentHash) {

        public Image {
            Objects.requireNonNull(png, "png bytes cannot be null");
            Objects.requireNonNull(contentHash, "contentHash cannot be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Image dimensions must be positive: " + width + "x" + height);
            }
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }

        /**
         * Dispatches an authorized image grant with a default source description.
         */
        public AuthorizedImageContent newGrant() {
            return newGrant("Native GLB material texture " + width + "x" + height);
        }

        /**
         * Dispatches an authorized image grant matching SDK ofDimensionsBounded 4-arg signature.
         */
        public AuthorizedImageContent newGrant(String sourceDescription) {
            Objects.requireNonNull(sourceDescription, "sourceDescription cannot be null");
            return AuthorizedImageContent.ofDimensionsBounded(png.clone(), sourceDescription, width, height);
        }
    }

    /**
     * Preparation options and budgets.
     */
    public record Options(
            long maxCumulativePixels, long maxCumulativePngBytes, int maxImageDimension, int constantImageDimension) {

        public static final Options DEFAULT = new Options(
                67_108_864L, // 64 Mpx cumulative
                128 * 1024 * 1024L, // 128 MiB PNG cumulative
                8192,
                16);
    }

    /* ---------------- Convenient accessors ---------------- */

    /**
     * Returns the deduplicated {@link Image} assigned to the mesh at {@code sourceMeshIndex},
     * or {@code null} if the index is out of range.
     */
    public Image imageForMesh(int sourceMeshIndex) {
        if (sourceMeshIndex < 0 || sourceMeshIndex >= meshImageIndices.size()) {
            return null;
        }
        int idx = meshImageIndices.get(sourceMeshIndex);
        return (idx >= 0 && idx < images.size()) ? images.get(idx) : null;
    }

    /**
     * Returns a 1:1 list of images aligned with {@code scene.meshes()}.
     */
    public List<Image> meshImages() {
        return meshImageIndices.stream()
                .map(idx -> (idx >= 0 && idx < images.size()) ? images.get(idx) : null)
                .toList();
    }

    /**
     * Returns the deduplicated image index for the mesh at {@code sourceMeshIndex},
     * or -1 if the index is out of range.
     */
    public int imageIndexForMesh(int sourceMeshIndex) {
        if (sourceMeshIndex < 0 || sourceMeshIndex >= meshImageIndices.size()) {
            return -1;
        }
        return meshImageIndices.get(sourceMeshIndex);
    }

    /* ---------------- Factory entry points ---------------- */

    public static NativeMaterialPlan prepare(ViewerScene scene) {
        return prepare(scene, Options.DEFAULT, () -> false);
    }

    public static NativeMaterialPlan prepare(ViewerScene scene, BooleanSupplier cancelled) {
        return prepare(scene, Options.DEFAULT, cancelled);
    }

    public static NativeMaterialPlan prepare(ViewerScene scene, Options options, BooleanSupplier cancelled) {
        Objects.requireNonNull(scene, "scene cannot be null");
        Objects.requireNonNull(options, "options cannot be null");
        checkCancelled(cancelled);

        List<ViewerScene.Mesh> originalMeshes = scene.meshes();
        if (originalMeshes.isEmpty()) {
            return new NativeMaterialPlan(scene, List.of(), List.of());
        }

        // 1. Normalize ViewerScene meshes:
        // When a mesh has no baseColorTexture but has an emissiveTexture, promote emissiveUvs to primary uvs.
        List<ViewerScene.Mesh> normalizedMeshes = new ArrayList<>(originalMeshes.size());
        for (ViewerScene.Mesh mesh : originalMeshes) {
            checkCancelled(cancelled);
            int matIdx = mesh.materialIndex();
            ViewerScene.Material mat =
                    (matIdx >= 0 && matIdx < scene.materials().size())
                            ? scene.materials().get(matIdx)
                            : null;
            float[] finalUvs = mesh.uvs();
            if (mat != null && mat.baseColorTexture() == -1 && mat.emissiveTexture() != -1) {
                float[] emissiveUvs = mesh.emissiveUvs();
                if (emissiveUvs != null && emissiveUvs.length > 0) {
                    finalUvs = emissiveUvs.clone();
                }
            }
            normalizedMeshes.add(new ViewerScene.Mesh(
                    mesh.name(),
                    mesh.positions(),
                    mesh.normals(),
                    finalUvs,
                    mesh.emissiveUvs(),
                    mesh.colors(),
                    mesh.indices(),
                    mesh.materialIndex()));
        }

        ViewerScene normalizedScene =
                new ViewerScene(normalizedMeshes, scene.materials(), scene.textures(), scene.bounds(), scene.stats());

        // 2. Bake and deduplicate textures for each mesh
        long cumulativePixels = 0;
        long cumulativePngBytes = 0;
        List<Image> images = new ArrayList<>();
        Map<String, Integer> hashToImageIndex = new HashMap<>();
        Map<String, BakedTexture> bakeCache = new HashMap<>();
        List<Integer> meshImageIndices = new ArrayList<>(originalMeshes.size());

        for (int i = 0; i < originalMeshes.size(); i++) {
            checkCancelled(cancelled);
            ViewerScene.Mesh mesh = originalMeshes.get(i);

            int matIdx = mesh.materialIndex();
            ViewerScene.Material mat =
                    (matIdx >= 0 && matIdx < scene.materials().size())
                            ? scene.materials().get(matIdx)
                            : null;

            // a) Validate multi-UV compatibility for THIS mesh unconditionally before checking cache
            if (mat != null && mat.baseColorTexture() != -1 && mat.emissiveTexture() != -1) {
                if (!uvsMatch(mesh.uvs(), mesh.emissiveUvs())) {
                    throw new IllegalArgumentException(
                            "Multi-UV compositing of baseColorTexture and emissiveTexture is unsupported: "
                                    + "base and emissive use different UV channels for mesh '" + mesh.name() + "'");
                }
            }

            float[] vertColor = getConstantVertexColor(mesh);
            String cacheKey =
                    matIdx + ":" + vertColor[0] + "," + vertColor[1] + "," + vertColor[2] + "," + vertColor[3];
            BakedTexture baked = bakeCache.get(cacheKey);
            if (baked == null) {
                // c) Pre-flight checks on dimensions and potential cumulative pixel budget before allocation
                int targetW, targetH;
                if (mat == null || (mat.baseColorTexture() == -1 && mat.emissiveTexture() == -1)) {
                    targetW = options.constantImageDimension();
                    targetH = options.constantImageDimension();
                } else {
                    ViewerScene.Texture baseTex = resolveTexture(scene, mat.baseColorTexture());
                    ViewerScene.Texture emissTex = resolveTexture(scene, mat.emissiveTexture());
                    int bw = baseTex != null ? baseTex.width() : 0;
                    int bh = baseTex != null ? baseTex.height() : 0;
                    int ew = emissTex != null ? emissTex.width() : 0;
                    int eh = emissTex != null ? emissTex.height() : 0;
                    targetW = Math.max(bw, ew);
                    targetH = Math.max(bh, eh);
                    if (targetW == 0 || targetH == 0) {
                        targetW = options.constantImageDimension();
                        targetH = options.constantImageDimension();
                    }
                }
                validateDimensions(targetW, targetH, options);
                long potentialPixels = cumulativePixels + (long) targetW * targetH;
                if (potentialPixels > options.maxCumulativePixels()) {
                    throw new IllegalArgumentException("Cumulative texture pixels (" + potentialPixels
                            + ") would exceed budget of " + options.maxCumulativePixels());
                }

                baked = bakeMeshTexture(mesh, scene, mat, vertColor, targetW, targetH, options, cancelled);
                bakeCache.put(cacheKey, baked);
            }

            String dedupKey = baked.width() + "x" + baked.height() + ":" + baked.contentHash();
            Integer existingIndex = hashToImageIndex.get(dedupKey);
            if (existingIndex != null) {
                meshImageIndices.add(existingIndex);
            } else {
                cumulativePixels += (long) baked.width() * baked.height();
                if (cumulativePixels > options.maxCumulativePixels()) {
                    throw new IllegalArgumentException("Cumulative texture pixels (" + cumulativePixels
                            + ") exceed budget of " + options.maxCumulativePixels());
                }
                cumulativePngBytes += baked.pngBytes().length;
                if (cumulativePngBytes > options.maxCumulativePngBytes()) {
                    throw new IllegalArgumentException("Cumulative PNG bytes (" + cumulativePngBytes
                            + ") exceed budget of " + options.maxCumulativePngBytes());
                }
                int newIndex = images.size();
                images.add(new Image(baked.width(), baked.height(), baked.pngBytes(), baked.contentHash()));
                hashToImageIndex.put(dedupKey, newIndex);
                meshImageIndices.add(newIndex);
            }
        }

        return new NativeMaterialPlan(normalizedScene, meshImageIndices, images);
    }

    /* ---------------- Internal Baking Logic ---------------- */

    private record BakedTexture(int width, int height, byte[] pngBytes, String contentHash) {}

    private static BakedTexture bakeMeshTexture(
            ViewerScene.Mesh mesh,
            ViewerScene scene,
            ViewerScene.Material mat,
            float[] vertColor,
            int targetW,
            int targetH,
            Options options,
            BooleanSupplier cancelled) {

        if (mat == null) {
            // Mesh has no material assigned; bake constant neutral/white color
            return bakeConstantImage(1.0f, 1.0f, 1.0f, 1.0f, vertColor, "OPAQUE", 0.0f, options);
        }

        ViewerScene.Texture baseTex = resolveTexture(scene, mat.baseColorTexture());
        ViewerScene.Texture emissTex = resolveTexture(scene, mat.emissiveTexture());

        float[] bf = mat.baseColorFactor();
        float bfR = (bf != null && bf.length > 0) ? bf[0] : 1.0f;
        float bfG = (bf != null && bf.length > 1) ? bf[1] : 1.0f;
        float bfB = (bf != null && bf.length > 2) ? bf[2] : 1.0f;
        float bfA = (bf != null && bf.length > 3) ? bf[3] : 1.0f;

        float[] ef = mat.emissiveFactor();
        float efR = (ef != null && ef.length > 0) ? ef[0] : 0.0f;
        float efG = (ef != null && ef.length > 1) ? ef[1] : 0.0f;
        float efB = (ef != null && ef.length > 2) ? ef[2] : 0.0f;

        String alphaMode = mat.alphaMode() != null ? mat.alphaMode() : "OPAQUE";
        float alphaCutoff = Math.max(0.0f, mat.alphaCutoff());

        // Case A: Neither texture present -> constant image
        if (baseTex == null && emissTex == null) {
            float linR = bfR * vertColor[0] + efR;
            float linG = bfG * vertColor[1] + efG;
            float linB = bfB * vertColor[2] + efB;
            float rawA = bfA * vertColor[3];
            return bakeConstantImage(
                    linR, linG, linB, rawA, new float[] {1f, 1f, 1f, 1f}, alphaMode, alphaCutoff, options);
        }

        // Case B: Only baseColorTexture present
        if (baseTex != null && emissTex == null) {
            return bakeBaseOnly(
                    baseTex, bfR, bfG, bfB, bfA, efR, efG, efB, vertColor, alphaMode, alphaCutoff, options, cancelled);
        }

        // Case C: Only emissiveTexture present
        if (baseTex == null && emissTex != null) {
            return bakeEmissiveOnly(
                    emissTex, bfR, bfG, bfB, bfA, efR, efG, efB, vertColor, alphaMode, alphaCutoff, options, cancelled);
        }

        // Case D: Both baseColorTexture and emissiveTexture present
        return bakeCombined(
                baseTex,
                emissTex,
                targetW,
                targetH,
                bfR,
                bfG,
                bfB,
                bfA,
                efR,
                efG,
                efB,
                vertColor,
                alphaMode,
                alphaCutoff,
                options,
                cancelled);
    }

    private static BakedTexture bakeConstantImage(
            float linR,
            float linG,
            float linB,
            float rawA,
            float[] vertColor,
            String alphaMode,
            float alphaCutoff,
            Options options) {

        int dim = options.constantImageDimension();
        float finalLinR = linR * vertColor[0];
        float finalLinG = linG * vertColor[1];
        float finalLinB = linB * vertColor[2];
        float finalRawA = rawA * vertColor[3];

        int outR = clamp255(linearToSrgb(finalLinR));
        int outG = clamp255(linearToSrgb(finalLinG));
        int outB = clamp255(linearToSrgb(finalLinB));

        int outA;
        if ("OPAQUE".equalsIgnoreCase(alphaMode)) {
            outA = 255;
        } else if ("MASK".equalsIgnoreCase(alphaMode)) {
            outA = (finalRawA >= alphaCutoff) ? 255 : 0;
        } else {
            outA = clamp255(finalRawA);
        }

        int argbPixel = (outA << 24) | (outR << 16) | (outG << 8) | outB;
        int[] pixels = new int[dim * dim];
        Arrays.fill(pixels, argbPixel);

        byte[] pngBytes = encodePng(dim, dim, pixels);
        String hash = computeSha256(dim, dim, pixels);
        return new BakedTexture(dim, dim, pngBytes, hash);
    }

    private static BakedTexture bakeBaseOnly(
            ViewerScene.Texture baseTex,
            float bfR,
            float bfG,
            float bfB,
            float bfA,
            float efR,
            float efG,
            float efB,
            float[] vertColor,
            String alphaMode,
            float alphaCutoff,
            Options options,
            BooleanSupplier cancelled) {

        int width = baseTex.width();
        int height = baseTex.height();
        validateDimensions(width, height, options);

        int[] srcPixels = baseTex.argb();
        int[] outPixels = new int[width * height];

        boolean isOpaqueMode = "OPAQUE".equalsIgnoreCase(alphaMode);
        boolean isMaskMode = "MASK".equalsIgnoreCase(alphaMode);

        float factorA = bfA * vertColor[3];
        boolean isColorIdentity = (bfR == 1.0f
                && bfG == 1.0f
                && bfB == 1.0f
                && efR == 0.0f
                && efG == 0.0f
                && efB == 0.0f
                && vertColor[0] == 1.0f
                && vertColor[1] == 1.0f
                && vertColor[2] == 1.0f);

        for (int y = 0; y < height; y++) {
            if ((y & 63) == 0) checkCancelled(cancelled);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int idx = rowOffset + x;
                int src = srcPixels[idx];
                int srcA = (src >>> 24) & 0xFF;
                int srcR = (src >>> 16) & 0xFF;
                int srcG = (src >>> 8) & 0xFF;
                int srcB = src & 0xFF;

                int outA;
                if (isOpaqueMode) {
                    outA = 255;
                } else {
                    float aNorm = (srcA / 255.0f) * factorA;
                    if (isMaskMode) {
                        outA = (aNorm >= alphaCutoff) ? 255 : 0;
                    } else {
                        outA = clamp255(aNorm);
                    }
                }

                int outR, outG, outB;
                if (isColorIdentity) {
                    outR = srcR;
                    outG = srcG;
                    outB = srcB;
                } else {
                    float linR = srgbToLinear(srcR / 255.0f) * bfR * vertColor[0] + efR;
                    float linG = srgbToLinear(srcG / 255.0f) * bfG * vertColor[1] + efG;
                    float linB = srgbToLinear(srcB / 255.0f) * bfB * vertColor[2] + efB;
                    outR = clamp255(linearToSrgb(linR));
                    outG = clamp255(linearToSrgb(linG));
                    outB = clamp255(linearToSrgb(linB));
                }

                outPixels[idx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }

        byte[] pngBytes = encodePng(width, height, outPixels);
        String hash = computeSha256(width, height, outPixels);
        return new BakedTexture(width, height, pngBytes, hash);
    }

    private static BakedTexture bakeEmissiveOnly(
            ViewerScene.Texture emissTex,
            float bfR,
            float bfG,
            float bfB,
            float bfA,
            float efR,
            float efG,
            float efB,
            float[] vertColor,
            String alphaMode,
            float alphaCutoff,
            Options options,
            BooleanSupplier cancelled) {

        int width = emissTex.width();
        int height = emissTex.height();
        validateDimensions(width, height, options);

        int[] srcPixels = emissTex.argb();
        int[] outPixels = new int[width * height];

        boolean isOpaqueMode = "OPAQUE".equalsIgnoreCase(alphaMode);
        boolean isMaskMode = "MASK".equalsIgnoreCase(alphaMode);

        float factorA = bfA * vertColor[3];
        int outA;
        if (isOpaqueMode) {
            outA = 255;
        } else if (isMaskMode) {
            outA = (factorA >= alphaCutoff) ? 255 : 0;
        } else {
            outA = clamp255(factorA);
        }

        float linBaseR = bfR * vertColor[0];
        float linBaseG = bfG * vertColor[1];
        float linBaseB = bfB * vertColor[2];

        boolean isEmissiveZero = (efR == 0.0f && efG == 0.0f && efB == 0.0f);
        boolean isPureEmissiveIdentity =
                (linBaseR == 0.0f && linBaseG == 0.0f && linBaseB == 0.0f && efR == 1.0f && efG == 1.0f && efB == 1.0f);

        for (int y = 0; y < height; y++) {
            if ((y & 63) == 0) checkCancelled(cancelled);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int idx = rowOffset + x;
                int src = srcPixels[idx];
                int srcR = (src >>> 16) & 0xFF;
                int srcG = (src >>> 8) & 0xFF;
                int srcB = src & 0xFF;

                int outR, outG, outB;
                if (isEmissiveZero) {
                    outR = clamp255(linearToSrgb(linBaseR));
                    outG = clamp255(linearToSrgb(linBaseG));
                    outB = clamp255(linearToSrgb(linBaseB));
                } else if (isPureEmissiveIdentity) {
                    outR = srcR;
                    outG = srcG;
                    outB = srcB;
                } else {
                    float linEmR = srgbToLinear(srcR / 255.0f) * efR;
                    float linEmG = srgbToLinear(srcG / 255.0f) * efG;
                    float linEmB = srgbToLinear(srcB / 255.0f) * efB;
                    outR = clamp255(linearToSrgb(linBaseR + linEmR));
                    outG = clamp255(linearToSrgb(linBaseG + linEmG));
                    outB = clamp255(linearToSrgb(linBaseB + linEmB));
                }

                outPixels[idx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }

        byte[] pngBytes = encodePng(width, height, outPixels);
        String hash = computeSha256(width, height, outPixels);
        return new BakedTexture(width, height, pngBytes, hash);
    }

    private static BakedTexture bakeCombined(
            ViewerScene.Texture baseTex,
            ViewerScene.Texture emissTex,
            int targetW,
            int targetH,
            float bfR,
            float bfG,
            float bfB,
            float bfA,
            float efR,
            float efG,
            float efB,
            float[] vertColor,
            String alphaMode,
            float alphaCutoff,
            Options options,
            BooleanSupplier cancelled) {

        int[] outPixels = new int[targetW * targetH];

        boolean isOpaqueMode = "OPAQUE".equalsIgnoreCase(alphaMode);
        boolean isMaskMode = "MASK".equalsIgnoreCase(alphaMode);

        float factorA = bfA * vertColor[3];
        boolean baseSameDim = (baseTex.width() == targetW && baseTex.height() == targetH);
        boolean emissSameDim = (emissTex.width() == targetW && emissTex.height() == targetH);

        int[] baseArgb = baseTex.argb();
        int[] emissArgb = emissTex.argb();

        for (int y = 0; y < targetH; y++) {
            if ((y & 63) == 0) checkCancelled(cancelled);
            int rowOffset = y * targetW;
            for (int x = 0; x < targetW; x++) {
                int idx = rowOffset + x;

                int basePix = baseSameDim
                        ? baseArgb[idx]
                        : sampleBilinear(baseTex, (x + 0.5f) / targetW, (y + 0.5f) / targetH);
                int emissPix = emissSameDim
                        ? emissArgb[idx]
                        : sampleBilinear(emissTex, (x + 0.5f) / targetW, (y + 0.5f) / targetH);

                int baseA = (basePix >>> 24) & 0xFF;
                int baseR = (basePix >>> 16) & 0xFF;
                int baseG = (basePix >>> 8) & 0xFF;
                int baseB = basePix & 0xFF;

                int emR = (emissPix >>> 16) & 0xFF;
                int emG = (emissPix >>> 8) & 0xFF;
                int emB = emissPix & 0xFF;

                int outA;
                if (isOpaqueMode) {
                    outA = 255;
                } else {
                    float aNorm = (baseA / 255.0f) * factorA;
                    if (isMaskMode) {
                        outA = (aNorm >= alphaCutoff) ? 255 : 0;
                    } else {
                        outA = clamp255(aNorm);
                    }
                }

                float linBaseR = srgbToLinear(baseR / 255.0f) * bfR * vertColor[0];
                float linBaseG = srgbToLinear(baseG / 255.0f) * bfG * vertColor[1];
                float linBaseB = srgbToLinear(baseB / 255.0f) * bfB * vertColor[2];

                float linEmR = srgbToLinear(emR / 255.0f) * efR;
                float linEmG = srgbToLinear(emG / 255.0f) * efG;
                float linEmB = srgbToLinear(emB / 255.0f) * efB;

                int outR = clamp255(linearToSrgb(linBaseR + linEmR));
                int outG = clamp255(linearToSrgb(linBaseG + linEmG));
                int outB = clamp255(linearToSrgb(linBaseB + linEmB));

                outPixels[idx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }

        byte[] pngBytes = encodePng(targetW, targetH, outPixels);
        String hash = computeSha256(targetW, targetH, outPixels);
        return new BakedTexture(targetW, targetH, pngBytes, hash);
    }

    /* ---------------- Utility Helpers ---------------- */

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.interrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new CancellationException("Native material preparation was cancelled");
        }
    }

    private static void validateDimensions(int width, int height, Options options) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Texture dimensions must be positive: " + width + "x" + height);
        }
        if (width > options.maxImageDimension() || height > options.maxImageDimension()) {
            throw new IllegalArgumentException("Texture dimensions (" + width + "x" + height
                    + ") exceed max dimension limit (" + options.maxImageDimension() + ")");
        }
        if ((long) width * height > 16_777_216L) { // 64 MiB RGBA budget
            throw new IllegalArgumentException(
                    "Texture pixels (" + ((long) width * height) + ") exceed single image RGBA budget (16777216)");
        }
    }

    private static ViewerScene.Texture resolveTexture(ViewerScene scene, int textureIndex) {
        if (textureIndex >= 0 && textureIndex < scene.textures().size()) {
            ViewerScene.Texture tex = scene.textures().get(textureIndex);
            if (tex != null && tex.width() > 0 && tex.height() > 0 && tex.argb() != null) {
                return tex;
            }
        }
        return null;
    }

    private static float[] getConstantVertexColor(ViewerScene.Mesh mesh) {
        float[] colors = mesh.colors();
        if (colors == null || colors.length < 4) {
            return new float[] {1.0f, 1.0f, 1.0f, 1.0f};
        }
        float r0 = colors[0];
        float g0 = colors[1];
        float b0 = colors[2];
        float a0 = colors[3];
        return new float[] {r0, g0, b0, a0};
    }

    private static boolean uvsMatch(float[] uvs1, float[] uvs2) {
        if (uvs1 == uvs2) {
            return true;
        }
        if (uvs1 == null || uvs2 == null || uvs1.length == 0 || uvs2.length == 0) {
            return true;
        }
        return Arrays.equals(uvs1, uvs2);
    }

    private static int sampleBilinear(ViewerScene.Texture tex, float u, float v) {
        int w = tex.width();
        int h = tex.height();
        int[] argb = tex.argb();

        // Wrap coordinates to [0, 1)
        u = u - (float) Math.floor(u);
        v = v - (float) Math.floor(v);

        float fx = u * w - 0.5f;
        float fy = v * h - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int x1 = x0 + 1;
        int y1 = y0 + 1;

        float dx = fx - x0;
        float dy = fy - y0;

        x0 = Math.max(0, Math.min(w - 1, x0));
        x1 = Math.max(0, Math.min(w - 1, x1));
        y0 = Math.max(0, Math.min(h - 1, y0));
        y1 = Math.max(0, Math.min(h - 1, y1));

        int p00 = argb[y0 * w + x0];
        int p10 = argb[y0 * w + x1];
        int p01 = argb[y1 * w + x0];
        int p11 = argb[y1 * w + x1];

        float w00 = (1.0f - dx) * (1.0f - dy);
        float w10 = dx * (1.0f - dy);
        float w01 = (1.0f - dx) * dy;
        float w11 = dx * dy;

        int a = Math.round(((p00 >>> 24) & 0xFF) * w00
                + ((p10 >>> 24) & 0xFF) * w10
                + ((p01 >>> 24) & 0xFF) * w01
                + ((p11 >>> 24) & 0xFF) * w11);
        int r = Math.round(((p00 >>> 16) & 0xFF) * w00
                + ((p10 >>> 16) & 0xFF) * w10
                + ((p01 >>> 16) & 0xFF) * w01
                + ((p11 >>> 16) & 0xFF) * w11);
        int g = Math.round(((p00 >>> 8) & 0xFF) * w00
                + ((p10 >>> 8) & 0xFF) * w10
                + ((p01 >>> 8) & 0xFF) * w01
                + ((p11 >>> 8) & 0xFF) * w11);
        int b = Math.round((p00 & 0xFF) * w00 + (p10 & 0xFF) * w10 + (p01 & 0xFF) * w01 + (p11 & 0xFF) * w11);

        return (Math.min(255, Math.max(0, a)) << 24)
                | (Math.min(255, Math.max(0, r)) << 16)
                | (Math.min(255, Math.max(0, g)) << 8)
                | Math.min(255, Math.max(0, b));
    }

    private static byte[] encodePng(int width, int height, int[] argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PNG for dimensions " + width + "x" + height, e);
        }
        return baos.toByteArray();
    }

    private static String computeSha256(int width, int height, int[] argb) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer bb = ByteBuffer.allocate(8 + argb.length * 4);
            bb.putInt(width);
            bb.putInt(height);
            for (int pixel : argb) {
                bb.putInt(pixel);
            }
            byte[] digest = md.digest(bb.array());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static float srgbToLinear(float c) {
        if (c <= 0.04045f) {
            return Math.max(0.0f, c / 12.92f);
        } else {
            return (float) Math.pow((Math.min(1.0f, c) + 0.055) / 1.055, 2.4);
        }
    }

    public static float linearToSrgb(float c) {
        if (c <= 0.0031308f) {
            return Math.max(0.0f, c * 12.92f);
        } else {
            return (float) (1.055 * Math.pow(Math.min(1.0, (double) c), 1.0 / 2.4) - 0.055);
        }
    }

    private static int clamp255(float val) {
        int v = Math.round(val * 255.0f);
        return Math.max(0, Math.min(255, v));
    }
}
