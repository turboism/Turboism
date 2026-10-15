package dev.turboism.plugin.modelimport3d.viewer;

import dev.turboism.sdk.json.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * High-performance GLB 2.0 loader for humanoid and 3D scenes.
 *
 * <p>Supports default scene hierarchy, node TRS and matrix transforms,
 * skinning (JOINTS_0, WEIGHTS_0, inverseBindMatrices), morph targets with default weights,
 * base color and emissive textures with respective UV coordinates, COLOR_0,
 * alpha modes (OPAQUE, MASK, BLEND), and material extensions (KHR_materials_unlit,
 * KHR_materials_emissive_strength).
 */
public final class GlbViewerLoader {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_TYPE_BIN = 0x004E4942; // "BIN\0"

    private static final long MAX_FILE_SIZE = 256 * 1024 * 1024L; // 256 MiB
    private static final int MAX_IMAGE_DIM = 8192;
    private static final long MAX_CUMULATIVE_PIXELS = 67_108_864L; // 64 Mpx
    private static final int MAX_VERTICES = 1_000_000;
    private static final int MAX_TRIANGLES = 2_000_000;
    private static final int MAX_NODES = 10_000;
    private static final int MAX_NODE_DEPTH = 64;

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("KHR_materials_unlit", "KHR_materials_emissive_strength");

    private GlbViewerLoader() {}

    /**
     * Loads a GLB 2.0 file into a {@link ViewerScene} in default pose.
     *
     * @param glb raw GLB bytes (non-null)
     * @param cancelled cancellation probe, may be null
     * @return fully decoded ViewerScene ready for rendering
     * @throws NullPointerException if {@code glb} is null
     * @throws CancellationException if {@code cancelled} signals true
     * @throws IllegalArgumentException if the GLB is invalid, exceeds budgets, or contains unsupported required features
     */
    public static ViewerScene load(final byte[] glb, final BooleanSupplier cancelled) {
        return load(glb, cancelled, false);
    }

    /** Loads standard glTF position Morph Targets as independent source controls, without external preprocessing. */
    public static ViewerScene loadWithSourceControls(final byte[] glb, final BooleanSupplier cancelled) {
        return load(glb, cancelled, true);
    }

    /** Routes rigs of any file size to the source-aware native importer. */
    public static boolean requiresNativeRigImport(byte[] glb) {
        if (glb == null || glb.length < 20 || glb.length > MAX_FILE_SIZE || getIntLE(glb, 0) != GLB_MAGIC) return false;
        int size = getIntLE(glb, 12);
        if (size < 0 || 20L + size > glb.length || getIntLE(glb, 16) != CHUNK_TYPE_JSON) return false;
        int end = 20 + size;
        while (end > 20
                && (glb[end - 1] == 0
                        || glb[end - 1] == ' '
                        || glb[end - 1] == '\n'
                        || glb[end - 1] == '\r'
                        || glb[end - 1] == '\t')) end--;
        var root = Json.parseObject(Arrays.copyOfRange(glb, 20, end));
        if (root.get("skins") instanceof List<?> skins && !skins.isEmpty()) return true;
        if (root.get("extras") instanceof Map<?, ?> extra
                && extra.get("turboismControls") instanceof List<?> controls
                && !controls.isEmpty()) return true;
        if (root.get("meshes") instanceof List<?> meshes)
            for (var mesh : meshes)
                if (mesh instanceof Map<?, ?> m && m.get("primitives") instanceof List<?> primitives)
                    for (var primitive : primitives)
                        if (primitive instanceof Map<?, ?> p
                                && p.get("targets") instanceof List<?> targets
                                && !targets.isEmpty()) return true;
        return false;
    }

    private static ViewerScene load(final byte[] glb, final BooleanSupplier cancelled, boolean importSourceControls) {
        Objects.requireNonNull(glb, "glb bytes cannot be null");
        checkCancelled(cancelled);

        if (glb.length < 20) {
            throw new IllegalArgumentException("GLB too short: " + glb.length + " bytes");
        }
        if (glb.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "GLB exceeds maximum budget of " + MAX_FILE_SIZE + " bytes: " + glb.length);
        }

        final int magic = getIntLE(glb, 0);
        if (magic != GLB_MAGIC) {
            throw new IllegalArgumentException("Invalid GLB magic: 0x" + Integer.toHexString(magic));
        }

        final int version = getIntLE(glb, 4);
        if (version != 2) {
            throw new IllegalArgumentException(
                    "Unsupported GLB version: " + version + " (only version 2 is supported)");
        }

        final long declaredLength = getIntLE(glb, 8) & 0xFFFFFFFFL;
        if (declaredLength != glb.length) {
            throw new IllegalArgumentException(
                    "GLB declared length mismatch: declared=" + declaredLength + " actual=" + glb.length);
        }

        // Chunk 0: JSON
        final int c0Len = getIntLE(glb, 12);
        final int c0Type = getIntLE(glb, 16);
        if (c0Type != CHUNK_TYPE_JSON) {
            throw new IllegalArgumentException(
                    "First GLB chunk must be JSON (0x4E4F534A), found: 0x" + Integer.toHexString(c0Type));
        }
        if (c0Len < 0 || 20L + c0Len > declaredLength) {
            throw new IllegalArgumentException(
                    "JSON chunk exceeds GLB boundaries: length=" + c0Len + " declaredLength=" + declaredLength);
        }

        checkCancelled(cancelled);

        // Trim trailing padding spaces/nulls from chunk 0
        int jsonEnd = 20 + c0Len;
        while (jsonEnd > 20
                && (glb[jsonEnd - 1] == ' '
                        || glb[jsonEnd - 1] == 0
                        || glb[jsonEnd - 1] == '\r'
                        || glb[jsonEnd - 1] == '\n'
                        || glb[jsonEnd - 1] == '\t')) {
            jsonEnd--;
        }
        final byte[] jsonBytes = Arrays.copyOfRange(glb, 20, jsonEnd);
        final Map<String, ?> root = Json.parseObject(jsonBytes);

        checkCancelled(cancelled);

        // Chunk 1: BIN
        byte[] binData = new byte[0];
        int c1HeaderOffset = 20 + c0Len;
        // GLB chunks are 4-byte aligned
        if ((c1HeaderOffset % 4) != 0) {
            c1HeaderOffset += (4 - (c1HeaderOffset % 4));
        }
        if (c1HeaderOffset + 8 <= declaredLength) {
            final int c1Len = getIntLE(glb, c1HeaderOffset);
            final int c1Type = getIntLE(glb, c1HeaderOffset + 4);
            if (c1Type == CHUNK_TYPE_BIN) {
                final int binStart = c1HeaderOffset + 8;
                if (c1Len < 0 || (long) binStart + c1Len > declaredLength) {
                    throw new IllegalArgumentException(
                            "BIN chunk exceeds GLB boundaries: length=" + c1Len + " declaredLength=" + declaredLength);
                }
                binData = Arrays.copyOfRange(glb, binStart, binStart + c1Len);
            }
        }

        // Check required extensions
        final List<?> extensionsRequired = asList(root.get("extensionsRequired"));
        if (extensionsRequired != null) {
            for (Object extObj : extensionsRequired) {
                if (extObj instanceof String ext) {
                    if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                        throw new IllegalArgumentException("Unsupported required extension: " + ext);
                    }
                }
            }
        }

        // Check for compressed extensions in extensionsUsed
        final List<?> extensionsUsed = asList(root.get("extensionsUsed"));
        if (extensionsUsed != null) {
            for (Object extObj : extensionsUsed) {
                if (extObj instanceof String ext) {
                    if (ext.contains("draco") || ext.contains("meshopt")) {
                        throw new IllegalArgumentException("Unsupported compressed extension: " + ext);
                    }
                }
            }
        }

        // Parse Buffers
        final List<?> buffersJson = asList(root.get("buffers"));
        if (buffersJson != null) {
            for (Object bufObj : buffersJson) {
                if (bufObj instanceof Map<?, ?> bufMap) {
                    if (bufMap.containsKey("uri")) {
                        throw new IllegalArgumentException(
                                "External URI in buffer is not permitted: " + bufMap.get("uri"));
                    }
                }
            }
        }

        // Parse BufferViews
        final List<BufferView> bufferViews = parseBufferViews(asList(root.get("bufferViews")), binData.length);

        // Parse Accessors
        final List<Accessor> accessors = parseAccessors(asList(root.get("accessors")), bufferViews, binData.length);

        checkCancelled(cancelled);

        // Parse Images and Textures
        final List<ViewerScene.Texture> textures =
                decodeImages(asList(root.get("images")), bufferViews, binData, cancelled);

        // Parse Materials
        final List<?> texturesJson = asList(root.get("textures"));
        final List<ViewerScene.Material> materials =
                parseMaterials(asList(root.get("materials")), texturesJson, textures.size());

        checkCancelled(cancelled);

        // Parse Nodes & Hierarchy
        final List<?> nodesJson = asList(root.get("nodes"));
        final int nodeCount = nodesJson != null ? nodesJson.size() : 0;
        if (nodeCount > MAX_NODES) {
            throw new IllegalArgumentException("Node count exceeds budget: " + nodeCount + " > " + MAX_NODES);
        }

        final List<NodeDef> nodes = parseNodes(nodesJson);
        final var controls = new ArrayList<>(parseControls(root));
        // Explicit sampled controls replace automatic endpoints; never duplicate the same rig twice.
        final boolean automaticMorphs = importSourceControls && controls.isEmpty();
        long controlSlots = 0;

        // Determine default scene
        final List<?> scenesJson = asList(root.get("scenes"));
        int defaultSceneIdx = getInt(root, "scene", 0);
        if (scenesJson == null || scenesJson.isEmpty()) {
            return new ViewerScene(
                    List.of(),
                    materials,
                    textures,
                    new ViewerScene.Bounds(0, 0, 0, 0, 0, 0),
                    new ViewerScene.Stats(glb.length, 0, 0, materials.size(), textures.size(), 0, 0));
        }
        if (defaultSceneIdx < 0 || defaultSceneIdx >= scenesJson.size()) {
            defaultSceneIdx = 0;
        }

        final Map<?, ?> sceneMap = (Map<?, ?>) scenesJson.get(defaultSceneIdx);
        final List<?> rootNodeIndices = asList(sceneMap.get("nodes"));

        // Compute world transforms for all nodes with cycle detection and depth budget
        final float[][] worldMatrices = new float[nodeCount][16];
        final boolean[] visited = new boolean[nodeCount];
        final boolean[] visiting = new boolean[nodeCount];

        if (rootNodeIndices != null) {
            final float[] identity = identityMatrix();
            for (Object rObj : rootNodeIndices) {
                if (rObj instanceof Number rNum) {
                    final int rootIdx = rNum.intValue();
                    if (rootIdx >= 0 && rootIdx < nodeCount) {
                        computeWorldTransforms(rootIdx, identity, nodes, worldMatrices, visited, visiting, 0);
                    }
                }
            }
        }

        checkCancelled(cancelled);

        // Parse Skins
        final List<?> skinsJson = asList(root.get("skins"));
        final List<SkinDef> skins = parseSkins(skinsJson, accessors, bufferViews, binData, worldMatrices);

        // Parse Meshes
        final List<?> meshesJson = asList(root.get("meshes"));
        final int glbMeshCount = meshesJson != null ? meshesJson.size() : 0;

        int maxMorphTargets = 0;
        if (meshesJson != null) {
            for (Object mObj : meshesJson) {
                if (mObj instanceof Map<?, ?> mMap) {
                    final List<?> prims = asList(mMap.get("primitives"));
                    if (prims != null && !prims.isEmpty() && prims.get(0) instanceof Map<?, ?> pMap) {
                        final List<?> targets = asList(pMap.get("targets"));
                        if (targets != null) {
                            maxMorphTargets = Math.max(maxMorphTargets, targets.size());
                        }
                    }
                }
            }
        }

        checkCancelled(cancelled);

        // Traverse scene and instantiate render meshes for all nodes with meshes
        final List<ViewerScene.Mesh> sceneMeshes = new ArrayList<>();
        int totalTriangles = 0;
        int totalVertices = 0;

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        if (nodesJson != null && meshesJson != null) {
            for (int nIdx = 0; nIdx < nodeCount; nIdx++) {
                if (!visited[nIdx]) {
                    continue; // Skip nodes not in default scene
                }
                final NodeDef node = nodes.get(nIdx);
                if (node.meshIndex < 0 || node.meshIndex >= meshesJson.size()) {
                    continue;
                }

                checkCancelled(cancelled);

                final Map<?, ?> meshJson = (Map<?, ?>) meshesJson.get(node.meshIndex);
                final boolean visible = !hiddenForRender(meshJson) && !hiddenForRender(nodesJson.get(nIdx));
                final String meshName =
                        getString(meshJson, "name", node.name != null ? node.name : "mesh_" + node.meshIndex);
                final List<?> primsJson = asList(meshJson.get("primitives"));
                if (primsJson == null || primsJson.isEmpty()) {
                    continue;
                }

                // Default weights: node weights take precedence over mesh weights
                final float[] defaultWeights;
                if (node.weights != null) {
                    defaultWeights = node.weights;
                } else {
                    final List<?> meshWeights = asList(meshJson.get("weights"));
                    if (meshWeights != null) {
                        defaultWeights = new float[meshWeights.size()];
                        for (int w = 0; w < meshWeights.size(); w++) {
                            float weightVal = ((Number) meshWeights.get(w)).floatValue();
                            if (Float.isNaN(weightVal) || Float.isInfinite(weightVal)) {
                                throw new IllegalArgumentException("Invalid morph weight in mesh " + node.meshIndex);
                            }
                            defaultWeights[w] = weightVal;
                        }
                    } else {
                        defaultWeights = null;
                    }
                }

                final SkinDef skin =
                        (node.skinIndex >= 0 && node.skinIndex < skins.size()) ? skins.get(node.skinIndex) : null;
                final float[] nodeWorldMatrix = worldMatrices[nIdx];

                for (int pIdx = 0; pIdx < primsJson.size(); pIdx++) {
                    checkCancelled(cancelled);

                    final Map<?, ?> primMap = (Map<?, ?>) primsJson.get(pIdx);
                    final Map<?, ?> attrs = (Map<?, ?>) primMap.get("attributes");
                    if (attrs == null || !attrs.containsKey("POSITION")) {
                        continue; // Invalid primitive without POSITION
                    }

                    final int posAccIdx = ((Number) attrs.get("POSITION")).intValue();
                    final Accessor posAcc = accessors.get(posAccIdx);
                    final int vertexCount = posAcc.count;
                    if (vertexCount <= 0) {
                        continue;
                    }

                    totalVertices += vertexCount;
                    if (totalVertices > MAX_VERTICES) {
                        throw new IllegalArgumentException("Vertex count exceeds budget of " + MAX_VERTICES);
                    }

                    // 1. Read base positions
                    final float[] positions = readFloatArray(posAcc, bufferViews, binData);

                    // 2. Read base normals (or generate default)
                    float[] normals;
                    if (attrs.containsKey("NORMAL")) {
                        final int normAccIdx = ((Number) attrs.get("NORMAL")).intValue();
                        normals = readFloatArray(accessors.get(normAccIdx), bufferViews, binData);
                    } else {
                        normals = new float[vertexCount * 3];
                        for (int v = 0; v < vertexCount; v++) {
                            normals[v * 3 + 1] = 1.0f; // Default up normal
                        }
                    }

                    // 3. Apply default morph targets in local space
                    final List<?> targets = asList(primMap.get("targets"));
                    if (targets != null && defaultWeights != null) {
                        for (int tIdx = 0; tIdx < targets.size(); tIdx++) {
                            if (tIdx >= defaultWeights.length) {
                                break;
                            }
                            final float weight = defaultWeights[tIdx];
                            if (weight == 0.0f) {
                                continue;
                            }
                            if (targets.get(tIdx) instanceof Map<?, ?> targetMap) {
                                if (targetMap.containsKey("POSITION")) {
                                    final int targetPosAcc = ((Number) targetMap.get("POSITION")).intValue();
                                    final float[] deltaPos =
                                            readFloatArray(accessors.get(targetPosAcc), bufferViews, binData);
                                    for (int i = 0; i < positions.length; i++) {
                                        positions[i] += weight * deltaPos[i];
                                    }
                                }
                                if (targetMap.containsKey("NORMAL")) {
                                    final int targetNormAcc = ((Number) targetMap.get("NORMAL")).intValue();
                                    final float[] deltaNorm =
                                            readFloatArray(accessors.get(targetNormAcc), bufferViews, binData);
                                    for (int i = 0; i < normals.length; i++) {
                                        normals[i] += weight * deltaNorm[i];
                                    }
                                }
                            }
                        }
                    }

                    // 4. Transform positions and normals (Skinning or Node World Transform)
                    final float[] worldPositions = new float[vertexCount * 3];
                    final float[] worldNormals = new float[vertexCount * 3];

                    final boolean hasSkin =
                            skin != null && attrs.containsKey("JOINTS_0") && attrs.containsKey("WEIGHTS_0");
                    if (hasSkin) {
                        final int jointsAccIdx = ((Number) attrs.get("JOINTS_0")).intValue();
                        final int weightsAccIdx = ((Number) attrs.get("WEIGHTS_0")).intValue();
                        final int[] joints = readIntArray(accessors.get(jointsAccIdx), bufferViews, binData);
                        final float[] weights = readFloatArray(accessors.get(weightsAccIdx), bufferViews, binData);

                        applySkinning(
                                positions,
                                normals,
                                joints,
                                weights,
                                skin.jointMatrices,
                                vertexCount,
                                worldPositions,
                                worldNormals);
                    } else {
                        applyNodeTransform(
                                positions, normals, nodeWorldMatrix, vertexCount, worldPositions, worldNormals);
                    }

                    // Update AABB bounds and validate against NaN/Infinity
                    for (int v = 0; v < vertexCount; v++) {
                        final float px = worldPositions[v * 3];
                        final float py = worldPositions[v * 3 + 1];
                        final float pz = worldPositions[v * 3 + 2];
                        if (Float.isNaN(px)
                                || Float.isInfinite(px)
                                || Float.isNaN(py)
                                || Float.isInfinite(py)
                                || Float.isNaN(pz)
                                || Float.isInfinite(pz)) {
                            throw new IllegalArgumentException(
                                    "NaN or Infinity position encountered during world transform");
                        }
                        if (!visible) continue;
                        if (px < minX) minX = px;
                        if (px > maxX) maxX = px;
                        if (py < minY) minY = py;
                        if (py > maxY) maxY = py;
                        if (pz < minZ) minZ = pz;
                        if (pz > maxZ) maxZ = pz;
                    }

                    // 5. Material and UVs
                    final int matIdx =
                            primMap.containsKey("material") ? ((Number) primMap.get("material")).intValue() : -1;
                    int baseColorTexCoord = 0;
                    int emissiveTexCoord = 0;
                    if (matIdx >= 0 && matIdx < materials.size()) {
                        final Map<?, ?> matJson = (Map<?, ?>) ((List<?>) root.get("materials")).get(matIdx);
                        final Map<?, ?> pbr = (Map<?, ?>) matJson.get("pbrMetallicRoughness");
                        if (pbr != null
                                && pbr.containsKey("baseColorTexture")
                                && pbr.get("baseColorTexture") instanceof Map<?, ?> bct) {
                            baseColorTexCoord = getInt(bct, "texCoord", 0);
                        }
                        if (matJson.containsKey("emissiveTexture")
                                && matJson.get("emissiveTexture") instanceof Map<?, ?> emt) {
                            emissiveTexCoord = getInt(emt, "texCoord", 0);
                        }
                    }

                    // Read UV coordinates
                    final float[] uvs =
                            readTexCoord(attrs, baseColorTexCoord, accessors, bufferViews, binData, vertexCount);
                    final float[] emissiveUvs = (emissiveTexCoord == baseColorTexCoord)
                            ? uvs.clone()
                            : readTexCoord(attrs, emissiveTexCoord, accessors, bufferViews, binData, vertexCount);

                    // 6. Vertex Colors (COLOR_0)
                    final float[] colors;
                    if (attrs.containsKey("COLOR_0")) {
                        final int colorAccIdx = ((Number) attrs.get("COLOR_0")).intValue();
                        colors = readColorArray(accessors.get(colorAccIdx), bufferViews, binData, vertexCount);
                    } else {
                        colors = new float[vertexCount * 4];
                        Arrays.fill(colors, 1.0f);
                    }

                    // 7. Indices
                    final int[] indices;
                    if (primMap.containsKey("indices")) {
                        final int idxAccIdx = ((Number) primMap.get("indices")).intValue();
                        indices = readIntArray(accessors.get(idxAccIdx), bufferViews, binData);
                    } else {
                        indices = new int[vertexCount];
                        for (int i = 0; i < vertexCount; i++) {
                            indices[i] = i;
                        }
                    }

                    // Validate indices
                    for (int idx : indices) {
                        if (idx < 0 || idx >= vertexCount) {
                            throw new IllegalArgumentException(
                                    "Index out of vertex bounds: " + idx + " (vertexCount=" + vertexCount + ")");
                        }
                    }

                    final int primTriangles = indices.length / 3;
                    totalTriangles += primTriangles;
                    if (totalTriangles > MAX_TRIANGLES) {
                        throw new IllegalArgumentException("Triangle count exceeds budget of " + MAX_TRIANGLES);
                    }

                    final var controlSamples = new ArrayList<ViewerScene.ControlSample>();
                    if (primMap.get("extras") instanceof Map<?, ?> extra
                            && extra.get("turboismControlSamples") instanceof List<?> samples) {
                        for (Object item : samples) {
                            checkCancelled(cancelled);
                            if (!(item instanceof Map<?, ?> sample))
                                throw new IllegalArgumentException("invalid control sample");
                            String parameter = getString(sample, "parameter", "");
                            float key = requiredControlFloat(sample, "key");
                            var definition = controls.stream()
                                    .filter(c -> c.id().equals(parameter))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("unknown source control"));
                            if (key < definition.minimum() || key > definition.maximum() || key == 0)
                                throw new IllegalArgumentException("source control key outside range");
                            controlSlots += vertexCount;
                            if (controlSlots > 16_000_000L)
                                throw new IllegalArgumentException("source control sample budget exceeded");
                            int ai = getInt(sample, "deltas", -1);
                            if (ai < 0 || ai >= accessors.size())
                                throw new IllegalArgumentException("invalid control delta accessor");
                            var acc = accessors.get(ai);
                            if (!"VEC3".equals(acc.type) || acc.componentType != 5126)
                                throw new IllegalArgumentException("control deltas require float VEC3");
                            var compact = readFloatArray(acc, bufferViews, binData);
                            float[] deltas;
                            if (sample.containsKey("slots")) {
                                int si = getInt(sample, "slots", -1);
                                if (si < 0 || si >= accessors.size())
                                    throw new IllegalArgumentException("invalid sparse control slots");
                                var slotsAcc = accessors.get(si);
                                if (!"SCALAR".equals(slotsAcc.type)
                                        || slotsAcc.componentType != 5125
                                        || slotsAcc.count != acc.count)
                                    throw new IllegalArgumentException("invalid sparse control shape");
                                var slots = readIntArray(slotsAcc, bufferViews, binData);
                                deltas = new float[vertexCount * 3];
                                int prior = -1;
                                for (int q = 0; q < slots.length; q++) {
                                    int slot = slots[q];
                                    if (slot <= prior || slot >= vertexCount)
                                        throw new IllegalArgumentException("invalid sparse control index");
                                    System.arraycopy(compact, q * 3, deltas, slot * 3, 3);
                                    prior = slot;
                                }
                            } else {
                                if (acc.count != vertexCount)
                                    throw new IllegalArgumentException("control vertex count differs");
                                deltas = compact;
                            }
                            for (float value : deltas)
                                if (!Float.isFinite(value))
                                    throw new IllegalArgumentException("nonfinite source control");
                            if (controlSamples.stream()
                                    .anyMatch(v -> v.controlId().equals(parameter) && v.key() == key))
                                throw new IllegalArgumentException("duplicate source control sample");
                            controlSamples.add(new ViewerScene.ControlSample(parameter, key, deltas));
                        }
                    }
                    if (automaticMorphs && visible && targets != null) {
                        var names = meshJson.get("extras") instanceof Map<?, ?> extra
                                ? asList(extra.get("targetNames"))
                                : null;
                        for (int t = 0; t < targets.size(); t++) {
                            checkCancelled(cancelled);
                            if (!(targets.get(t) instanceof Map<?, ?> target)
                                    || !(target.get("POSITION") instanceof Number index)) continue;
                            int ai = index.intValue();
                            if (ai < 0 || ai >= accessors.size())
                                throw new IllegalArgumentException("invalid morph position accessor");
                            var acc = accessors.get(ai);
                            if (!"VEC3".equals(acc.type) || acc.componentType != 5126 || acc.count != vertexCount)
                                throw new IllegalArgumentException("morph positions require matching float VEC3");
                            float saved = defaultWeights != null && t < defaultWeights.length ? defaultWeights[t] : 0f;
                            float minimum = Math.min(0f, saved) - saved, maximum = Math.max(1f, saved) - saved;
                            int endpoints = (minimum != 0 ? 1 : 0) + (maximum != 0 ? 1 : 0);
                            if (controlSlots + (long) vertexCount * endpoints > 16_000_000L)
                                throw new IllegalArgumentException("source control sample budget exceeded");
                            var delta = readFloatArray(acc, bufferViews, binData);
                            boolean effective = false;
                            for (float v : delta) {
                                if (!Float.isFinite(v)) throw new IllegalArgumentException("nonfinite morph position");
                                if (v != 0f) effective = true;
                            }
                            if (!effective) continue;
                            String id = "gltf.node." + nIdx + ".morph." + t;
                            String label = names != null
                                            && t < names.size()
                                            && names.get(t) instanceof String name
                                            && !name.isBlank()
                                    ? name
                                    : meshName + " / Morph " + (t + 1);
                            var definition = new ViewerScene.Control(id, label, minimum, maximum);
                            var prior = controls.stream()
                                    .filter(c -> c.id().equals(id))
                                    .findFirst();
                            if (prior.isEmpty()) {
                                if (controls.size() >= 256)
                                    throw new IllegalArgumentException("source control count exceeds 256");
                                controls.add(definition);
                            } else if (!prior.get().equals(definition))
                                throw new IllegalArgumentException("inconsistent morph definition across primitives");
                            for (float key : new float[] {minimum, maximum}) {
                                if (key == 0f) continue;
                                var posed = positions.clone();
                                for (int i = 0; i < posed.length; i++) posed[i] += delta[i] * key;
                                var world = new float[posed.length];
                                var ignoredNormals = new float[normals.length];
                                if (hasSkin) {
                                    var joints = readIntArray(
                                            accessors.get(((Number) attrs.get("JOINTS_0")).intValue()),
                                            bufferViews,
                                            binData);
                                    var weights = readFloatArray(
                                            accessors.get(((Number) attrs.get("WEIGHTS_0")).intValue()),
                                            bufferViews,
                                            binData);
                                    applySkinning(
                                            posed,
                                            normals,
                                            joints,
                                            weights,
                                            skin.jointMatrices,
                                            vertexCount,
                                            world,
                                            ignoredNormals);
                                } else
                                    applyNodeTransform(
                                            posed, normals, nodeWorldMatrix, vertexCount, world, ignoredNormals);
                                for (int i = 0; i < world.length; i++) {
                                    world[i] -= worldPositions[i];
                                    if (!Float.isFinite(world[i]))
                                        throw new IllegalArgumentException("nonfinite transformed morph");
                                }
                                controlSamples.add(new ViewerScene.ControlSample(id, key, world));
                                controlSlots += vertexCount;
                            }
                        }
                    }
                    final var opacityControls =
                            parseOpacityControls((Map<?, ?>) nodesJson.get(nIdx), primMap, controls);
                    final String primName = (primsJson.size() > 1) ? (meshName + "_" + pIdx) : meshName;
                    var sourceMesh = new ViewerScene.Mesh(
                            primName,
                            worldPositions,
                            worldNormals,
                            uvs,
                            emissiveUvs,
                            colors,
                            indices,
                            matIdx,
                            visible,
                            (node.name != null ? node.name : meshName) + " [" + nIdx + "]",
                            primitiveBodyPart(primMap),
                            controlSamples,
                            opacityControls);
                    if (importSourceControls
                            && visible
                            && hasSkin
                            && sourceMesh.bodyPart().isBlank()) {
                        var joints = readIntArray(
                                accessors.get(((Number) attrs.get("JOINTS_0")).intValue()), bufferViews, binData);
                        var weights = readFloatArray(
                                accessors.get(((Number) attrs.get("WEIGHTS_0")).intValue()), bufferViews, binData);
                        long originalSamples =
                                (long) vertexCount * sourceMesh.controlSamples().size();
                        var grouped = groupBySourceJoint(
                                sourceMesh,
                                joints,
                                weights,
                                skin,
                                nodes,
                                16_000_000L - controlSlots + originalSamples,
                                MAX_VERTICES - totalVertices + vertexCount);
                        long groupedSamples = grouped.stream()
                                .mapToLong(m -> (long) (m.positions().length / 3)
                                        * m.controlSamples().size())
                                .sum();
                        totalVertices += grouped.stream()
                                        .mapToInt(m -> m.positions().length / 3)
                                        .sum()
                                - vertexCount;
                        controlSlots += groupedSamples - originalSamples;
                        sceneMeshes.addAll(grouped);
                    } else sceneMeshes.add(sourceMesh);
                }
            }
        }

        // Final bounds check
        if (sceneMeshes.isEmpty() || Float.isInfinite(minX)) {
            minX = minY = minZ = maxX = maxY = maxZ = 0.0f;
        }

        final ViewerScene.Bounds bounds = new ViewerScene.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        final ViewerScene.Stats stats = new ViewerScene.Stats(
                glb.length,
                glbMeshCount,
                totalTriangles,
                materials.size(),
                textures.size(),
                skins.size(),
                maxMorphTargets);

        return new ViewerScene(sceneMeshes, materials, textures, bounds, stats, controls);
    }

    /** Uses actual vertex influences. Bone labels are source labels, not anatomical guesses. */
    private static List<ViewerScene.Mesh> groupBySourceJoint(
            ViewerScene.Mesh source,
            int[] joints,
            float[] weights,
            SkinDef skin,
            List<NodeDef> nodes,
            long sampleBudget,
            long vertexBudget) {
        var groups = new java.util.LinkedHashMap<Integer, List<Integer>>();
        int[] indices = source.indices();
        for (int t = 0; t + 2 < indices.length; t += 3) {
            var votes = new java.util.TreeMap<Integer, Double>();
            for (int k = 0; k < 3; k++)
                for (int influence = 0; influence < 4; influence++) {
                    int slot = indices[t + k] * 4 + influence;
                    int joint = joints[slot];
                    float weight = weights[slot];
                    if (weight > 0 && joint >= 0 && joint < skin.joints.length)
                        votes.merge(joint, (double) weight, Double::sum);
                }
            int joint = -1;
            double maximum = 0;
            for (var vote : votes.entrySet())
                if (vote.getValue() > maximum) {
                    joint = vote.getKey();
                    maximum = vote.getValue();
                }
            var triangles = groups.computeIfAbsent(joint, ignored -> new ArrayList<>());
            for (int k = 0; k < 3; k++) triangles.add(indices[t + k]);
        }
        long requiredVertices = groups.values().stream()
                .mapToLong(v -> v.stream().distinct().count())
                .sum();
        if (requiredVertices > vertexBudget
                || requiredVertices * source.controlSamples().size() > sampleBudget)
            throw new IllegalArgumentException("source joint grouping exceeds vertex/control budget");
        var result = new ArrayList<ViewerScene.Mesh>();
        for (var group : groups.entrySet()) {
            var mapping = new java.util.LinkedHashMap<Integer, Integer>();
            var compactIndices = new int[group.getValue().size()];
            for (int i = 0; i < compactIndices.length; i++)
                compactIndices[i] = mapping.computeIfAbsent(group.getValue().get(i), ignored -> mapping.size());
            int[] slots = mapping.keySet().stream().mapToInt(Integer::intValue).toArray();
            int joint = group.getKey(), node = joint < 0 ? -1 : skin.joints[joint];
            String label = node >= 0 && node < nodes.size() ? nodes.get(node).name : null;
            String part = node < 0
                    ? source.appearanceGroup()
                    : (label == null || label.isBlank() ? "Joint" : label) + " [node " + node + "]";
            var samples = source.controlSamples().stream()
                    .map(sample -> new ViewerScene.ControlSample(
                            sample.controlId(), sample.key(), compactSlots(sample.deltas(), 3, slots)))
                    .toList();
            result.add(new ViewerScene.Mesh(
                    source.name(),
                    compactSlots(source.positions(), 3, slots),
                    compactSlots(source.normals(), 3, slots),
                    compactSlots(source.uvs(), 2, slots),
                    compactSlots(source.emissiveUvs(), 2, slots),
                    compactSlots(source.colors(), 4, slots),
                    compactIndices,
                    source.materialIndex(),
                    source.visible(),
                    source.appearanceGroup(),
                    part,
                    samples,
                    source.opacityControls()));
        }
        return result;
    }

    private static float[] compactSlots(float[] source, int width, int[] slots) {
        if (source == null) return null;
        var result = new float[slots.length * width];
        for (int i = 0; i < slots.length; i++) System.arraycopy(source, slots[i] * width, result, i * width, width);
        return result;
    }

    private static String primitiveBodyPart(Map<?, ?> primitive) {
        if (primitive.get("extras") instanceof Map<?, ?> extras
                && extras.get("bodyPart") instanceof String part
                && !part.isBlank()) return part;
        return "";
    }

    // Explicit source metadata controls visibility; geometry remains in the scene.
    private static boolean hiddenForRender(Object value) {
        return value instanceof Map<?, ?> object
                && object.get("extras") instanceof Map<?, ?> extras
                && (Boolean.FALSE.equals(extras.get("defaultVisible"))
                        || Boolean.TRUE.equals(extras.get("hide_render"))
                        || Boolean.TRUE.equals(extras.get("isHelper")));
    }

    // --- Private Helper Methods ---

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("GLB load cancelled");
        }
    }

    private static List<ViewerScene.Texture> decodeImages(
            final List<?> imagesJson,
            final List<BufferView> bufferViews,
            final byte[] binData,
            final BooleanSupplier cancelled) {
        if (imagesJson == null || imagesJson.isEmpty()) {
            return List.of();
        }

        final List<ViewerScene.Texture> textures = new ArrayList<>(imagesJson.size());
        long cumulativePixels = 0;

        for (int i = 0; i < imagesJson.size(); i++) {
            checkCancelled(cancelled);
            final Map<?, ?> imgMap = (Map<?, ?>) imagesJson.get(i);
            if (imgMap.containsKey("uri")) {
                throw new IllegalArgumentException("External URI in image is not permitted: " + imgMap.get("uri"));
            }
            if (!imgMap.containsKey("bufferView")) {
                throw new IllegalArgumentException("Image " + i + " has no bufferView");
            }

            final int bvIdx = ((Number) imgMap.get("bufferView")).intValue();
            if (bvIdx < 0 || bvIdx >= bufferViews.size()) {
                throw new IllegalArgumentException("Image bufferView index out of bounds: " + bvIdx);
            }

            final BufferView bv = bufferViews.get(bvIdx);
            if (bv.byteOffset + bv.byteLength > binData.length) {
                throw new IllegalArgumentException("Image bufferView exceeds binary chunk bounds");
            }

            final ByteArrayInputStream bais = new ByteArrayInputStream(binData, bv.byteOffset, bv.byteLength);
            try (ImageInputStream iis = ImageIO.createImageInputStream(bais)) {
                if (iis == null) {
                    throw new IllegalArgumentException("Failed to create ImageInputStream for image " + i);
                }
                final Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    throw new IllegalArgumentException("Unsupported image format for image " + i);
                }
                final ImageReader reader = readers.next();
                try {
                    final String format = reader.getFormatName().toUpperCase();
                    if (!format.equals("PNG") && !format.equals("JPEG")) {
                        throw new IllegalArgumentException("Only PNG and JPEG images are supported, found: " + format);
                    }
                    reader.setInput(iis, true, true);
                    final int w = reader.getWidth(0);
                    final int h = reader.getHeight(0);
                    if (w <= 0 || h <= 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM) {
                        throw new IllegalArgumentException("Image " + i + " dimension exceeds limit: " + w + "x" + h);
                    }

                    final long imagePixels = (long) w * h;
                    if (cumulativePixels + imagePixels > MAX_CUMULATIVE_PIXELS) {
                        throw new IllegalArgumentException("Cumulative decoded pixels exceed budget of "
                                + MAX_CUMULATIVE_PIXELS + ": " + (cumulativePixels + imagePixels));
                    }

                    final BufferedImage img = reader.read(0);
                    if (img == null) {
                        throw new IllegalArgumentException("Failed to decode image " + i);
                    }

                    cumulativePixels += imagePixels;
                    final int[] argb = new int[w * h];
                    img.getRGB(0, 0, w, h, argb, 0, w);
                    textures.add(new ViewerScene.Texture(w, h, argb));
                } finally {
                    reader.dispose();
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read image " + i, e);
            }
        }

        return textures;
    }

    private static List<ViewerScene.Material> parseMaterials(
            final List<?> materialsJson, final List<?> texturesJson, final int textureCount) {
        if (materialsJson == null || materialsJson.isEmpty()) {
            return List.of();
        }

        final List<ViewerScene.Material> materials = new ArrayList<>(materialsJson.size());
        for (int i = 0; i < materialsJson.size(); i++) {
            final Map<?, ?> mat = (Map<?, ?>) materialsJson.get(i);
            final String name = getString(mat, "name", "material_" + i);

            // Base color factor & texture
            final float[] baseColorFactor = new float[] {1.0f, 1.0f, 1.0f, 1.0f};
            int baseColorTexture = -1;
            final Map<?, ?> pbr = (Map<?, ?>) mat.get("pbrMetallicRoughness");
            if (pbr != null) {
                final List<?> bcf = asList(pbr.get("baseColorFactor"));
                if (bcf != null && bcf.size() >= 4) {
                    for (int c = 0; c < 4; c++) {
                        baseColorFactor[c] = ((Number) bcf.get(c)).floatValue();
                    }
                }
                if (pbr.containsKey("baseColorTexture") && pbr.get("baseColorTexture") instanceof Map<?, ?> bct) {
                    final int texIdx = getInt(bct, "index", -1);
                    baseColorTexture = mapTextureToImage(texIdx, texturesJson, textureCount);
                }
            }

            // Emissive texture & factor
            int emissiveTexture = -1;
            if (mat.containsKey("emissiveTexture") && mat.get("emissiveTexture") instanceof Map<?, ?> emt) {
                final int texIdx = getInt(emt, "index", -1);
                emissiveTexture = mapTextureToImage(texIdx, texturesJson, textureCount);
            }

            final float[] emissiveFactor = new float[] {0.0f, 0.0f, 0.0f};
            final List<?> emf = asList(mat.get("emissiveFactor"));
            if (emf != null && emf.size() >= 3) {
                for (int c = 0; c < 3; c++) {
                    emissiveFactor[c] = ((Number) emf.get(c)).floatValue();
                }
            }

            // Extensions: KHR_materials_unlit, KHR_materials_emissive_strength
            boolean unlit = false;
            final Map<?, ?> exts = (Map<?, ?>) mat.get("extensions");
            if (exts != null) {
                if (exts.containsKey("KHR_materials_unlit")) {
                    unlit = true;
                }
                if (exts.containsKey("KHR_materials_emissive_strength")
                        && exts.get("KHR_materials_emissive_strength") instanceof Map<?, ?> ems) {
                    final float strength = getFloat(ems, "emissiveStrength", 1.0f);
                    emissiveFactor[0] *= strength;
                    emissiveFactor[1] *= strength;
                    emissiveFactor[2] *= strength;
                }
            }

            final String alphaMode = getString(mat, "alphaMode", "OPAQUE");
            final float alphaCutoff = getFloat(mat, "alphaCutoff", 0.5f);
            final boolean doubleSided = getBoolean(mat, "doubleSided", false);

            materials.add(new ViewerScene.Material(
                    name,
                    baseColorFactor,
                    baseColorTexture,
                    emissiveTexture,
                    emissiveFactor,
                    alphaMode,
                    alphaCutoff,
                    doubleSided,
                    unlit));
        }

        return materials;
    }

    private static int mapTextureToImage(final int texIdx, final List<?> texturesJson, final int textureCount) {
        if (texIdx < 0 || texturesJson == null || texIdx >= texturesJson.size()) {
            return -1;
        }
        if (texturesJson.get(texIdx) instanceof Map<?, ?> texMap) {
            final int sourceImg = getInt(texMap, "source", -1);
            if (sourceImg >= 0 && sourceImg < textureCount) {
                return sourceImg;
            }
        }
        return -1;
    }

    private static List<BufferView> parseBufferViews(final List<?> bufferViewsJson, final int binLength) {
        if (bufferViewsJson == null) {
            return List.of();
        }
        final List<BufferView> bvs = new ArrayList<>(bufferViewsJson.size());
        for (int i = 0; i < bufferViewsJson.size(); i++) {
            final Map<?, ?> bvMap = (Map<?, ?>) bufferViewsJson.get(i);
            final int buffer = getInt(bvMap, "buffer", 0);
            if (buffer != 0) {
                throw new IllegalArgumentException("Only buffer index 0 is supported in embedded GLB: " + buffer);
            }
            final int byteOffset = getInt(bvMap, "byteOffset", 0);
            final int byteLength = getInt(bvMap, "byteLength", 0);
            final int byteStride = getInt(bvMap, "byteStride", 0);
            if (byteOffset < 0 || byteLength < 0 || (long) byteOffset + byteLength > binLength) {
                throw new IllegalArgumentException("BufferView " + i + " out of bounds");
            }
            if (byteStride != 0 && (byteStride < 4 || byteStride > 252 || byteStride % 4 != 0)) {
                throw new IllegalArgumentException("BufferView " + i + " invalid byteStride: " + byteStride);
            }
            bvs.add(new BufferView(buffer, byteOffset, byteLength, byteStride));
        }
        return bvs;
    }

    private static List<Accessor> parseAccessors(
            final List<?> accessorsJson, final List<BufferView> bufferViews, final int binLength) {
        if (accessorsJson == null) {
            return List.of();
        }
        final List<Accessor> accs = new ArrayList<>(accessorsJson.size());
        for (int i = 0; i < accessorsJson.size(); i++) {
            final Map<?, ?> accMap = (Map<?, ?>) accessorsJson.get(i);
            final int bvIdx = getInt(accMap, "bufferView", -1);
            if (bvIdx < 0 || bvIdx >= bufferViews.size()) {
                throw new IllegalArgumentException("Accessor " + i + " references invalid bufferView: " + bvIdx);
            }
            final int byteOffset = getInt(accMap, "byteOffset", 0);
            if (byteOffset < 0) {
                throw new IllegalArgumentException("Accessor " + i + " byteOffset is negative: " + byteOffset);
            }
            final int componentType = getInt(accMap, "componentType", 0);
            final int count = getInt(accMap, "count", 0);
            if (count < 0) {
                throw new IllegalArgumentException("Accessor " + i + " count is negative: " + count);
            }
            final String type = getString(accMap, "type", "SCALAR");
            final boolean normalized = getBoolean(accMap, "normalized", false);
            final int numComponents =
                    switch (type) {
                        case "SCALAR" -> 1;
                        case "VEC2" -> 2;
                        case "VEC3" -> 3;
                        case "VEC4" -> 4;
                        case "MAT4" -> 16;
                        default -> throw new IllegalArgumentException("Unsupported accessor type: " + type);
                    };

            final int compSize = componentSize(componentType);
            if (byteOffset % compSize != 0) {
                throw new IllegalArgumentException(
                        "Accessor " + i + " byteOffset " + byteOffset + " is not aligned to componentSize " + compSize);
            }

            final BufferView bv = bufferViews.get(bvIdx);
            final int elementSize = numComponents * compSize;
            final int effectiveStride = bv.byteStride != 0 ? bv.byteStride : elementSize;
            if (effectiveStride < elementSize) {
                throw new IllegalArgumentException(
                        "Accessor " + i + " elementSize " + elementSize + " exceeds stride " + effectiveStride);
            }

            if (count > 0) {
                final long requiredBytes = (long) byteOffset + (long) (count - 1) * effectiveStride + elementSize;
                if (requiredBytes > bv.byteLength) {
                    throw new IllegalArgumentException("Accessor " + i + " exceeds bufferView " + bvIdx + ": required="
                            + requiredBytes + " length=" + bv.byteLength);
                }
                if ((long) bv.byteOffset + requiredBytes > binLength) {
                    throw new IllegalArgumentException("Accessor " + i + " exceeds binary chunk length");
                }
            }

            accs.add(new Accessor(bvIdx, byteOffset, componentType, count, type, numComponents, normalized));
        }
        return accs;
    }

    private static List<ViewerScene.Control> parseControls(Map<?, ?> root) {
        if (!(root.get("extras") instanceof Map<?, ?> extras)
                || !(extras.get("turboismControls") instanceof List<?> raw)) return List.of();
        if (raw.size() > 256) throw new IllegalArgumentException("too many source controls");
        var result = new ArrayList<ViewerScene.Control>();
        var ids = new java.util.HashSet<String>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map))
                throw new IllegalArgumentException("invalid source control definition");
            String id = getString(map, "id", "");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate source control definition");
            result.add(new ViewerScene.Control(
                    id,
                    getString(map, "name", ""),
                    requiredControlFloat(map, "minimum"),
                    requiredControlFloat(map, "maximum")));
        }
        return List.copyOf(result);
    }

    private static float requiredControlFloat(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number n && Float.isFinite(n.floatValue())) return n.floatValue();
        throw new IllegalArgumentException("missing finite control value: " + key);
    }

    private static List<ViewerScene.OpacityControl> parseOpacityControls(
            Map<?, ?> node, Map<?, ?> primitive, List<ViewerScene.Control> controls) {
        var result = new ArrayList<ViewerScene.OpacityControl>();
        var seen = new java.util.HashSet<String>();
        for (Map<?, ?> owner : List.of(node, primitive)) {
            if (!(owner.get("extras") instanceof Map<?, ?> extra)
                    || !(extra.get("turboismOpacityControls") instanceof List<?> raw)) continue;
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> factor)) throw new IllegalArgumentException("invalid opacity factor");
                String id = getString(factor, "parameter", "");
                if (!seen.add(id)
                        || controls.stream()
                                .noneMatch(c -> c.id().equals(id) && c.minimum() == 0f && c.maximum() == 1f))
                    throw new IllegalArgumentException("invalid opacity control definition");
                result.add(new ViewerScene.OpacityControl(
                        id, requiredControlFloat(factor, "base"), requiredControlFloat(factor, "target")));
            }
        }
        return List.copyOf(result);
    }

    private static List<NodeDef> parseNodes(final List<?> nodesJson) {
        if (nodesJson == null) {
            return List.of();
        }
        final List<NodeDef> nodes = new ArrayList<>(nodesJson.size());
        for (int i = 0; i < nodesJson.size(); i++) {
            final Map<?, ?> nodeMap = (Map<?, ?>) nodesJson.get(i);
            final String name = getString(nodeMap, "name", null);
            final int mesh = getInt(nodeMap, "mesh", -1);
            final int skin = getInt(nodeMap, "skin", -1);

            final List<?> childrenList = asList(nodeMap.get("children"));
            final int[] children;
            if (childrenList != null) {
                children = new int[childrenList.size()];
                for (int c = 0; c < childrenList.size(); c++) {
                    children[c] = ((Number) childrenList.get(c)).intValue();
                }
            } else {
                children = new int[0];
            }

            final List<?> weightsList = asList(nodeMap.get("weights"));
            final float[] weights;
            if (weightsList != null) {
                weights = new float[weightsList.size()];
                for (int w = 0; w < weightsList.size(); w++) {
                    float weightVal = ((Number) weightsList.get(w)).floatValue();
                    if (Float.isNaN(weightVal) || Float.isInfinite(weightVal)) {
                        throw new IllegalArgumentException("Invalid morph weight in node " + i);
                    }
                    weights[w] = weightVal;
                }
            } else {
                weights = null;
            }

            // Local transform matrix
            final float[] localMatrix;
            final List<?> matList = asList(nodeMap.get("matrix"));
            if (matList != null && matList.size() >= 16) {
                localMatrix = new float[16];
                for (int m = 0; m < 16; m++) {
                    localMatrix[m] = ((Number) matList.get(m)).floatValue();
                    if (Float.isNaN(localMatrix[m]) || Float.isInfinite(localMatrix[m])) {
                        throw new IllegalArgumentException("NaN or Infinity in node matrix: node=" + i);
                    }
                }
            } else {
                // TRS
                final float tx = getFloat(nodeMap, "translation", 0, 0.0f);
                final float ty = getFloat(nodeMap, "translation", 1, 0.0f);
                final float tz = getFloat(nodeMap, "translation", 2, 0.0f);

                final float qx = getFloat(nodeMap, "rotation", 0, 0.0f);
                final float qy = getFloat(nodeMap, "rotation", 1, 0.0f);
                final float qz = getFloat(nodeMap, "rotation", 2, 0.0f);
                final float qw = getFloat(nodeMap, "rotation", 3, 1.0f);

                final float sx = getFloat(nodeMap, "scale", 0, 1.0f);
                final float sy = getFloat(nodeMap, "scale", 1, 1.0f);
                final float sz = getFloat(nodeMap, "scale", 2, 1.0f);

                localMatrix = composeTrs(tx, ty, tz, qx, qy, qz, qw, sx, sy, sz);
            }

            nodes.add(new NodeDef(name, mesh, skin, children, weights, localMatrix));
        }
        return nodes;
    }

    private static void computeWorldTransforms(
            final int nodeIdx,
            final float[] parentMatrix,
            final List<NodeDef> nodes,
            final float[][] worldMatrices,
            final boolean[] visited,
            final boolean[] visiting,
            final int depth) {
        if (depth > MAX_NODE_DEPTH) {
            throw new IllegalArgumentException(
                    "Node hierarchy depth exceeds limit of " + MAX_NODE_DEPTH + " at node " + nodeIdx);
        }
        if (visiting[nodeIdx]) {
            throw new IllegalArgumentException("Illegal cycle detected in scene nodes at node " + nodeIdx);
        }
        if (visited[nodeIdx]) {
            return;
        }

        visiting[nodeIdx] = true;
        final NodeDef node = nodes.get(nodeIdx);
        final float[] world = multiplyMatrices(parentMatrix, node.localMatrix);
        worldMatrices[nodeIdx] = world;

        for (int childIdx : node.children) {
            if (childIdx >= 0 && childIdx < nodes.size()) {
                computeWorldTransforms(childIdx, world, nodes, worldMatrices, visited, visiting, depth + 1);
            }
        }

        visiting[nodeIdx] = false;
        visited[nodeIdx] = true;
    }

    private static List<SkinDef> parseSkins(
            final List<?> skinsJson,
            final List<Accessor> accessors,
            final List<BufferView> bufferViews,
            final byte[] binData,
            final float[][] worldMatrices) {
        if (skinsJson == null || skinsJson.isEmpty()) {
            return List.of();
        }

        final List<SkinDef> skins = new ArrayList<>(skinsJson.size());
        for (int sIdx = 0; sIdx < skinsJson.size(); sIdx++) {
            final Map<?, ?> skinMap = (Map<?, ?>) skinsJson.get(sIdx);
            final List<?> jointsJson = asList(skinMap.get("joints"));
            if (jointsJson == null || jointsJson.isEmpty()) {
                skins.add(new SkinDef(new int[0], new float[0][]));
                continue;
            }

            final int jointCount = jointsJson.size();
            final int[] joints = new int[jointCount];
            for (int j = 0; j < jointCount; j++) {
                joints[j] = ((Number) jointsJson.get(j)).intValue();
            }

            // Inverse bind matrices
            final float[][] ibms = new float[jointCount][16];
            if (skinMap.containsKey("inverseBindMatrices")) {
                final int ibmAccIdx = ((Number) skinMap.get("inverseBindMatrices")).intValue();
                final Accessor ibmAcc = accessors.get(ibmAccIdx);
                final BufferView bv = bufferViews.get(ibmAcc.bufferView);
                final int stride = bv.byteStride != 0 ? bv.byteStride : 64;
                for (int j = 0; j < jointCount; j++) {
                    final int off = bv.byteOffset + ibmAcc.byteOffset + j * stride;
                    for (int m = 0; m < 16; m++) {
                        float val = Float.intBitsToFloat(getIntLE(binData, off + m * 4));
                        if (Float.isNaN(val) || Float.isInfinite(val)) {
                            throw new IllegalArgumentException(
                                    "NaN or Infinity in inverseBindMatrices at skin " + sIdx + " joint " + j);
                        }
                        ibms[j][m] = val;
                    }
                }
            } else {
                for (int j = 0; j < jointCount; j++) {
                    ibms[j] = identityMatrix();
                }
            }

            // Compute joint matrices: JointWorld * IBM
            final float[][] jointMatrices = new float[jointCount][16];
            for (int j = 0; j < jointCount; j++) {
                final int jointNode = joints[j];
                final float[] jw = (jointNode >= 0 && jointNode < worldMatrices.length)
                        ? worldMatrices[jointNode]
                        : identityMatrix();
                jointMatrices[j] = multiplyMatrices(jw, ibms[j]);
            }

            skins.add(new SkinDef(joints, jointMatrices));
        }

        return skins;
    }

    private static void applySkinning(
            final float[] srcPos,
            final float[] srcNorm,
            final int[] joints,
            final float[] weights,
            final float[][] jointMatrices,
            final int vertexCount,
            final float[] dstPos,
            final float[] dstNorm) {
        for (int v = 0; v < vertexCount; v++) {
            final int v4 = v * 4;
            final int v3 = v * 3;

            final int j0 = joints[v4];
            final int j1 = joints[v4 + 1];
            final int j2 = joints[v4 + 2];
            final int j3 = joints[v4 + 3];

            float w0 = weights[v4];
            float w1 = weights[v4 + 1];
            float w2 = weights[v4 + 2];
            float w3 = weights[v4 + 3];

            if (Float.isNaN(w0)
                    || Float.isInfinite(w0)
                    || Float.isNaN(w1)
                    || Float.isInfinite(w1)
                    || Float.isNaN(w2)
                    || Float.isInfinite(w2)
                    || Float.isNaN(w3)
                    || Float.isInfinite(w3)) {
                throw new IllegalArgumentException("NaN or Infinity in vertex skin weights at vertex " + v);
            }

            final float sumW = w0 + w1 + w2 + w3;
            if (sumW > 0.0f && Math.abs(sumW - 1.0f) > 1e-4f) {
                final float invW = 1.0f / sumW;
                w0 *= invW;
                w1 *= invW;
                w2 *= invW;
                w3 *= invW;
            }

            final float x = srcPos[v3];
            final float y = srcPos[v3 + 1];
            final float z = srcPos[v3 + 2];

            final float nx = srcNorm[v3];
            final float ny = srcNorm[v3 + 1];
            final float nz = srcNorm[v3 + 2];

            float px = 0, py = 0, pz = 0;
            float rnx = 0, rny = 0, rnz = 0;

            if (w0 > 0 && j0 >= 0 && j0 < jointMatrices.length) {
                final float[] m = jointMatrices[j0];
                px += w0 * (m[0] * x + m[4] * y + m[8] * z + m[12]);
                py += w0 * (m[1] * x + m[5] * y + m[9] * z + m[13]);
                pz += w0 * (m[2] * x + m[6] * y + m[10] * z + m[14]);
                rnx += w0 * (m[0] * nx + m[4] * ny + m[8] * nz);
                rny += w0 * (m[1] * nx + m[5] * ny + m[9] * nz);
                rnz += w0 * (m[2] * nx + m[6] * ny + m[10] * nz);
            }
            if (w1 > 0 && j1 >= 0 && j1 < jointMatrices.length) {
                final float[] m = jointMatrices[j1];
                px += w1 * (m[0] * x + m[4] * y + m[8] * z + m[12]);
                py += w1 * (m[1] * x + m[5] * y + m[9] * z + m[13]);
                pz += w1 * (m[2] * x + m[6] * y + m[10] * z + m[14]);
                rnx += w1 * (m[0] * nx + m[4] * ny + m[8] * nz);
                rny += w1 * (m[1] * nx + m[5] * ny + m[9] * nz);
                rnz += w1 * (m[2] * nx + m[6] * ny + m[10] * nz);
            }
            if (w2 > 0 && j2 >= 0 && j2 < jointMatrices.length) {
                final float[] m = jointMatrices[j2];
                px += w2 * (m[0] * x + m[4] * y + m[8] * z + m[12]);
                py += w2 * (m[1] * x + m[5] * y + m[9] * z + m[13]);
                pz += w2 * (m[2] * x + m[6] * y + m[10] * z + m[14]);
                rnx += w2 * (m[0] * nx + m[4] * ny + m[8] * nz);
                rny += w2 * (m[1] * nx + m[5] * ny + m[9] * nz);
                rnz += w2 * (m[2] * nx + m[6] * ny + m[10] * nz);
            }
            if (w3 > 0 && j3 >= 0 && j3 < jointMatrices.length) {
                final float[] m = jointMatrices[j3];
                px += w3 * (m[0] * x + m[4] * y + m[8] * z + m[12]);
                py += w3 * (m[1] * x + m[5] * y + m[9] * z + m[13]);
                pz += w3 * (m[2] * x + m[6] * y + m[10] * z + m[14]);
                rnx += w3 * (m[0] * nx + m[4] * ny + m[8] * nz);
                rny += w3 * (m[1] * nx + m[5] * ny + m[9] * nz);
                rnz += w3 * (m[2] * nx + m[6] * ny + m[10] * nz);
            }

            dstPos[v3] = px;
            dstPos[v3 + 1] = py;
            dstPos[v3 + 2] = pz;

            final float nLen = (float) Math.sqrt(rnx * rnx + rny * rny + rnz * rnz);
            if (nLen > 1e-6f) {
                final float invN = 1.0f / nLen;
                dstNorm[v3] = rnx * invN;
                dstNorm[v3 + 1] = rny * invN;
                dstNorm[v3 + 2] = rnz * invN;
            } else {
                dstNorm[v3] = 0.0f;
                dstNorm[v3 + 1] = 1.0f;
                dstNorm[v3 + 2] = 0.0f;
            }
        }
    }

    private static void applyNodeTransform(
            final float[] srcPos,
            final float[] srcNorm,
            final float[] matrix,
            final int vertexCount,
            final float[] dstPos,
            final float[] dstNorm) {
        // Compute 3x3 normal matrix (cofactor matrix of upper 3x3)
        final float a00 = matrix[0], a10 = matrix[1], a20 = matrix[2];
        final float a01 = matrix[4], a11 = matrix[5], a21 = matrix[6];
        final float a02 = matrix[8], a12 = matrix[9], a22 = matrix[10];

        final float c00 = a11 * a22 - a12 * a21;
        final float c01 = a12 * a20 - a10 * a22;
        final float c02 = a10 * a21 - a11 * a20;

        final float c10 = a02 * a21 - a01 * a22;
        final float c11 = a00 * a22 - a02 * a20;
        final float c12 = a01 * a20 - a00 * a21;

        final float c20 = a01 * a12 - a02 * a11;
        final float c21 = a02 * a10 - a00 * a12;
        final float c22 = a00 * a11 - a01 * a10;

        for (int v = 0; v < vertexCount; v++) {
            final int v3 = v * 3;
            final float x = srcPos[v3];
            final float y = srcPos[v3 + 1];
            final float z = srcPos[v3 + 2];

            float rx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
            float ry = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
            float rz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
            final float rw = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
            if (rw != 1.0f && rw != 0.0f) {
                final float invW = 1.0f / rw;
                rx *= invW;
                ry *= invW;
                rz *= invW;
            }

            dstPos[v3] = rx;
            dstPos[v3 + 1] = ry;
            dstPos[v3 + 2] = rz;

            final float nx = srcNorm[v3];
            final float ny = srcNorm[v3 + 1];
            final float nz = srcNorm[v3 + 2];

            final float rnx = c00 * nx + c10 * ny + c20 * nz;
            final float rny = c01 * nx + c11 * ny + c21 * nz;
            final float rnz = c02 * nx + c12 * ny + c22 * nz;

            final float nLen = (float) Math.sqrt(rnx * rnx + rny * rny + rnz * rnz);
            if (nLen > 1e-6f) {
                final float invN = 1.0f / nLen;
                dstNorm[v3] = rnx * invN;
                dstNorm[v3 + 1] = rny * invN;
                dstNorm[v3 + 2] = rnz * invN;
            } else {
                dstNorm[v3] = 0.0f;
                dstNorm[v3 + 1] = 1.0f;
                dstNorm[v3 + 2] = 0.0f;
            }
        }
    }

    private static float[] readTexCoord(
            final Map<?, ?> attrs,
            final int texCoordIndex,
            final List<Accessor> accessors,
            final List<BufferView> bufferViews,
            final byte[] binData,
            final int vertexCount) {
        String key = "TEXCOORD_" + texCoordIndex;
        if (!attrs.containsKey(key)) {
            key = "TEXCOORD_0";
        }
        if (!attrs.containsKey(key)) {
            return new float[vertexCount * 2];
        }

        final int accIdx = ((Number) attrs.get(key)).intValue();
        final Accessor acc = accessors.get(accIdx);
        return readFloatArray(acc, bufferViews, binData);
    }

    private static float[] readColorArray(
            final Accessor acc, final List<BufferView> bufferViews, final byte[] binData, final int vertexCount) {
        final BufferView bv = bufferViews.get(acc.bufferView);
        final int compCount = acc.numComponents; // 3 (VEC3) or 4 (VEC4)
        final int compByteSize = componentSize(acc.componentType);
        final int stride = bv.byteStride != 0 ? bv.byteStride : compCount * compByteSize;

        final float[] colors = new float[vertexCount * 4];
        for (int i = 0; i < vertexCount; i++) {
            final int off = bv.byteOffset + acc.byteOffset + i * stride;
            final int c4 = i * 4;
            for (int c = 0; c < compCount; c++) {
                final int compOff = off + c * compByteSize;
                colors[c4 + c] = readNormalizedFloat(binData, compOff, acc.componentType, acc.normalized);
            }
            if (compCount == 3) {
                colors[c4 + 3] = 1.0f; // Alpha defaults to 1.0
            }
        }
        return colors;
    }

    private static float[] readFloatArray(
            final Accessor acc, final List<BufferView> bufferViews, final byte[] binData) {
        final BufferView bv = bufferViews.get(acc.bufferView);
        final int compCount = acc.numComponents;
        final int compByteSize = componentSize(acc.componentType);
        final int stride = bv.byteStride != 0 ? bv.byteStride : compCount * compByteSize;

        final float[] result = new float[acc.count * compCount];
        for (int i = 0; i < acc.count; i++) {
            final int elemOff = bv.byteOffset + acc.byteOffset + i * stride;
            for (int c = 0; c < compCount; c++) {
                final int off = elemOff + c * compByteSize;
                final float val;
                if (acc.componentType == 5126) { // FLOAT
                    val = Float.intBitsToFloat(getIntLE(binData, off));
                } else {
                    val = readNormalizedFloat(binData, off, acc.componentType, acc.normalized);
                }
                if (Float.isNaN(val) || Float.isInfinite(val)) {
                    throw new IllegalArgumentException("NaN or Infinity encountered in float accessor");
                }
                result[i * compCount + c] = val;
            }
        }
        return result;
    }

    private static int[] readIntArray(final Accessor acc, final List<BufferView> bufferViews, final byte[] binData) {
        final BufferView bv = bufferViews.get(acc.bufferView);
        final int compCount = acc.numComponents;
        final int compByteSize = componentSize(acc.componentType);
        final int stride = bv.byteStride != 0 ? bv.byteStride : compCount * compByteSize;

        final int[] result = new int[acc.count * compCount];
        for (int i = 0; i < acc.count; i++) {
            final int elemOff = bv.byteOffset + acc.byteOffset + i * stride;
            for (int c = 0; c < compCount; c++) {
                final int off = elemOff + c * compByteSize;
                final int val =
                        switch (acc.componentType) {
                            case 5120 -> binData[off]; // BYTE
                            case 5121 -> binData[off] & 0xFF; // UNSIGNED_BYTE
                            case 5122 -> getShortLE(binData, off); // SHORT
                            case 5123 -> getShortLE(binData, off) & 0xFFFF; // UNSIGNED_SHORT
                            case 5125 -> getIntLE(binData, off); // UNSIGNED_INT
                            default ->
                                throw new IllegalArgumentException(
                                        "Unsupported integer componentType: " + acc.componentType);
                        };
                result[i * compCount + c] = val;
            }
        }
        return result;
    }

    private static float readNormalizedFloat(
            final byte[] b, final int off, final int componentType, final boolean normalized) {
        final float val =
                switch (componentType) {
                    case 5126 -> Float.intBitsToFloat(getIntLE(b, off)); // FLOAT
                    case 5120 -> normalized ? Math.max(-1.0f, (float) b[off] / 127.0f) : (float) b[off]; // BYTE
                    case 5121 -> normalized ? (float) (b[off] & 0xFF) / 255.0f : (float) (b[off] & 0xFF); // UBYTE
                    case 5122 ->
                        normalized
                                ? Math.max(-1.0f, (float) getShortLE(b, off) / 32767.0f)
                                : (float) getShortLE(b, off); // SHORT
                    case 5123 ->
                        normalized
                                ? (float) (getShortLE(b, off) & 0xFFFF) / 65535.0f
                                : (float) (getShortLE(b, off) & 0xFFFF); // USHORT
                    case 5125 -> (float) ((long) getIntLE(b, off) & 0xFFFFFFFFL); // UINT
                    default -> throw new IllegalArgumentException("Unknown componentType: " + componentType);
                };
        if (Float.isNaN(val) || Float.isInfinite(val)) {
            throw new IllegalArgumentException("NaN or Infinity encountered in normalized float");
        }
        return val;
    }

    private static int componentSize(final int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IllegalArgumentException("Unknown componentType size: " + componentType);
        };
    }

    private static float[] composeTrs(
            final float tx,
            final float ty,
            final float tz,
            float qx,
            float qy,
            float qz,
            float qw,
            final float sx,
            final float sy,
            final float sz) {
        if (Float.isNaN(tx)
                || Float.isInfinite(tx)
                || Float.isNaN(ty)
                || Float.isInfinite(ty)
                || Float.isNaN(tz)
                || Float.isInfinite(tz)
                || Float.isNaN(qx)
                || Float.isInfinite(qx)
                || Float.isNaN(qy)
                || Float.isInfinite(qy)
                || Float.isNaN(qz)
                || Float.isInfinite(qz)
                || Float.isNaN(qw)
                || Float.isInfinite(qw)
                || Float.isNaN(sx)
                || Float.isInfinite(sx)
                || Float.isNaN(sy)
                || Float.isInfinite(sy)
                || Float.isNaN(sz)
                || Float.isInfinite(sz)) {
            throw new IllegalArgumentException("NaN or Infinity in node TRS parameters");
        }

        final float qLen = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (qLen > 0.0f) {
            final float invQ = 1.0f / qLen;
            qx *= invQ;
            qy *= invQ;
            qz *= invQ;
            qw *= invQ;
        }

        final float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        final float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        final float wx = qw * qx, wy = qw * qy, wz = qw * qz;

        final float[] m = new float[16];
        // Col 0
        m[0] = (1.0f - 2.0f * (yy + zz)) * sx;
        m[1] = (2.0f * (xy + wz)) * sx;
        m[2] = (2.0f * (xz - wy)) * sx;
        m[3] = 0.0f;
        // Col 1
        m[4] = (2.0f * (xy - wz)) * sy;
        m[5] = (1.0f - 2.0f * (xx + zz)) * sy;
        m[6] = (2.0f * (yz + wx)) * sy;
        m[7] = 0.0f;
        // Col 2
        m[8] = (2.0f * (xz + wy)) * sz;
        m[9] = (2.0f * (yz - wx)) * sz;
        m[10] = (1.0f - 2.0f * (xx + yy)) * sz;
        m[11] = 0.0f;
        // Col 3
        m[12] = tx;
        m[13] = ty;
        m[14] = tz;
        m[15] = 1.0f;
        return m;
    }

    private static float[] multiplyMatrices(final float[] a, final float[] b) {
        final float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            final int bCol = col * 4;
            for (int row = 0; row < 4; row++) {
                r[bCol + row] = a[row] * b[bCol]
                        + a[4 + row] * b[bCol + 1]
                        + a[8 + row] * b[bCol + 2]
                        + a[12 + row] * b[bCol + 3];
            }
        }
        return r;
    }

    private static float[] identityMatrix() {
        return new float[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
    }

    private static short getShortLE(final byte[] b, final int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static int getIntLE(final byte[] b, final int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int getInt(final Map<?, ?> map, final String key, final int def) {
        final Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static float getFloat(final Map<?, ?> map, final String key, final float def) {
        final Object v = map.get(key);
        return v instanceof Number n ? n.floatValue() : def;
    }

    private static float getFloat(final Map<?, ?> map, final String key, final int index, final float def) {
        final Object v = map.get(key);
        if (v instanceof List<?> list && index < list.size()) {
            final Object item = list.get(index);
            return item instanceof Number n ? n.floatValue() : def;
        }
        return def;
    }

    private static boolean getBoolean(final Map<?, ?> map, final String key, final boolean def) {
        final Object v = map.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static String getString(final Map<?, ?> map, final String key, final String def) {
        final Object v = map.get(key);
        return v instanceof String s ? s : def;
    }

    private static List<?> asList(final Object obj) {
        return obj instanceof List<?> list ? list : null;
    }

    // --- Private Inner Records ---

    private record BufferView(int buffer, int byteOffset, int byteLength, int byteStride) {}

    private record Accessor(
            int bufferView,
            int byteOffset,
            int componentType,
            int count,
            String type,
            int numComponents,
            boolean normalized) {}

    private record NodeDef(
            String name, int meshIndex, int skinIndex, int[] children, float[] weights, float[] localMatrix) {}

    private record SkinDef(int[] joints, float[][] jointMatrices) {}
}
