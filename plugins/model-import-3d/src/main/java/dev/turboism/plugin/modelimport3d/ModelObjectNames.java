package dev.turboism.plugin.modelimport3d;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats localized Cubism Part, Deformer, ArtMesh, and Parameter names for imported 3D models.
 */
final class ModelObjectNames {
    static final String KEY_ROTATION_PREFIX = "object.rotation.";
    static final String KEY_ROTATION_X = "object.rotation.x";
    static final String KEY_ROTATION_Y = "object.rotation.y";
    static final String KEY_ROTATION_Z = "object.rotation.z";
    static final String KEY_PLACEMENT = "object.placement";
    static final String KEY_WARP = "object.warp";
    static final String KEY_LOCAL_ACTION = "object.local-action";
    static final String KEY_PARTITION = "object.partition";
    static final String KEY_SURFACE = "object.surface";
    static final String KEY_DEFORMER = "object.deformer";
    static final String KEY_JOINT = "object.joint";
    static final String KEY_MESH = "object.mesh";
    static final String KEY_MESH_NAMED = "object.mesh.named";

    static final String FALLBACK_ROTATION_X = "X 旋转";
    static final String FALLBACK_ROTATION_Y = "Y 旋转";
    static final String FALLBACK_ROTATION_Z = "Z 旋转";
    static final String FALLBACK_ROTATION_SUFFIX = " 旋转";
    static final String FALLBACK_PLACEMENT = "整体定位";
    static final String FALLBACK_WARP = "曲面变形";
    static final String FALLBACK_LOCAL_ACTION = "分区 {0} 局部动作";
    static final String FALLBACK_PARTITION = "{0} 分区 {1}";
    static final String FALLBACK_SURFACE = "{0} 曲面 {1}";
    static final String FALLBACK_DEFORMER = "{0} 变形器 {1}";
    static final String FALLBACK_JOINT = "关节 {0}";
    static final String FALLBACK_MESH = "{0} 网格 {1}";
    static final String FALLBACK_MESH_NAMED = "{0} {1} 网格 {2}";
    static final String LEGACY_DEFAULT_GROUP_PREFIX = "Mesh ";

    private static final Pattern SYNTHETIC_MESH_NAME = Pattern.compile("^mesh_\\d+(?:_\\d+)?$");
    private static final Pattern SYNTHETIC_NODE_GROUP = Pattern.compile("^mesh_\\d+ \\[(\\d+)]$");
    private static final Pattern SYNTHETIC_JOINT_PART = Pattern.compile("^Joint \\[node (\\d+)]$");

    private ModelObjectNames() {}

    /** Builds the complete map of localized object name templates from a plugin localization lookup. */
    static Map<String, String> labels(BiFunction<String, String, String> textLookup) {
        Objects.requireNonNull(textLookup, "textLookup");
        return Map.ofEntries(
                Map.entry(KEY_ROTATION_X, textLookup.apply(KEY_ROTATION_X, FALLBACK_ROTATION_X)),
                Map.entry(KEY_ROTATION_Y, textLookup.apply(KEY_ROTATION_Y, FALLBACK_ROTATION_Y)),
                Map.entry(KEY_ROTATION_Z, textLookup.apply(KEY_ROTATION_Z, FALLBACK_ROTATION_Z)),
                Map.entry(KEY_PLACEMENT, textLookup.apply(KEY_PLACEMENT, FALLBACK_PLACEMENT)),
                Map.entry(KEY_WARP, textLookup.apply(KEY_WARP, FALLBACK_WARP)),
                Map.entry(KEY_LOCAL_ACTION, textLookup.apply(KEY_LOCAL_ACTION, FALLBACK_LOCAL_ACTION)),
                Map.entry(KEY_PARTITION, textLookup.apply(KEY_PARTITION, FALLBACK_PARTITION)),
                Map.entry(KEY_SURFACE, textLookup.apply(KEY_SURFACE, FALLBACK_SURFACE)),
                Map.entry(KEY_DEFORMER, textLookup.apply(KEY_DEFORMER, FALLBACK_DEFORMER)),
                Map.entry(KEY_JOINT, textLookup.apply(KEY_JOINT, FALLBACK_JOINT)),
                Map.entry(KEY_MESH, textLookup.apply(KEY_MESH, FALLBACK_MESH)),
                Map.entry(KEY_MESH_NAMED, textLookup.apply(KEY_MESH_NAMED, FALLBACK_MESH_NAMED)));
    }

    /** Replaces indexed placeholders without treating apostrophes in model names as MessageFormat quotes. */
    static String formatTemplate(String template, Object... arguments) {
        String formatted = Objects.requireNonNull(template, "template");
        for (int index = 0; index < arguments.length; index++) {
            formatted = formatted.replace("{" + index + "}", String.valueOf(arguments[index]));
        }
        return formatted;
    }

    static String rotationName(String modelName, Map<String, String> labels, String axis) {
        String key = KEY_ROTATION_PREFIX + axis.toLowerCase(Locale.ROOT);
        return modelName + " " + labels.getOrDefault(key, axis + FALLBACK_ROTATION_SUFFIX);
    }

    static String placementName(String modelName, Map<String, String> labels) {
        return modelName + " " + labels.getOrDefault(KEY_PLACEMENT, FALLBACK_PLACEMENT);
    }

    static String warpName(String modelName, Map<String, String> labels) {
        return modelName + " " + labels.getOrDefault(KEY_WARP, FALLBACK_WARP);
    }

    static String localActionName(Map<String, String> labels, int partitionIndex) {
        return formatTemplate(labels.getOrDefault(KEY_LOCAL_ACTION, FALLBACK_LOCAL_ACTION), partitionIndex);
    }

    static String partitionName(String modelName, Map<String, String> labels, int partitionIndex) {
        return formatTemplate(labels.getOrDefault(KEY_PARTITION, FALLBACK_PARTITION), modelName, partitionIndex);
    }

    static String surfaceName(String modelName, Map<String, String> labels, int surfaceIndex) {
        return formatTemplate(labels.getOrDefault(KEY_SURFACE, FALLBACK_SURFACE), modelName, surfaceIndex);
    }

    static String deformerName(String modelName, Map<String, String> labels, int deformerIndex) {
        return formatTemplate(labels.getOrDefault(KEY_DEFORMER, FALLBACK_DEFORMER), modelName, deformerIndex);
    }

    static String defaultDeformerGroup(SurfaceImport.Request request, int sourceMeshIndex) {
        if (request.labels().containsKey(KEY_DEFORMER)) {
            return deformerName(request.name(), request.labels(), sourceMeshIndex);
        }
        return LEGACY_DEFAULT_GROUP_PREFIX + sourceMeshIndex;
    }

    static String meshName(String modelName, Map<String, String> labels, int meshIndex) {
        return formatTemplate(labels.getOrDefault(KEY_MESH, FALLBACK_MESH), modelName, meshIndex);
    }

    /** Formats a partition ArtMesh name while collapsing blank, duplicate, or synthetic GLB mesh identifiers. */
    static String partitionMeshName(
            String modelName, Map<String, String> labels, String sourceMeshName, int partitionIndex) {
        if (!labels.containsKey(KEY_MESH)) {
            return modelName + " " + sourceMeshName + " " + partitionIndex;
        }
        String trimmed = sourceMeshName == null ? "" : sourceMeshName.strip();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase(modelName)
                || SYNTHETIC_MESH_NAME.matcher(trimmed).matches()) {
            return meshName(modelName, labels, partitionIndex);
        }
        return formatTemplate(
                labels.getOrDefault(KEY_MESH_NAMED, FALLBACK_MESH_NAMED), modelName, trimmed, partitionIndex);
    }

    /** Localizes synthetic fallback node group names while preserving authored GLB node and mesh names. */
    static String appearanceGroupName(
            String modelName, Map<String, String> labels, String rawGroup, boolean singleSourceMesh) {
        if (rawGroup == null || (!labels.containsKey(KEY_DEFORMER) && !labels.containsKey(KEY_WARP))) {
            return rawGroup;
        }
        Matcher synthetic = SYNTHETIC_NODE_GROUP.matcher(rawGroup);
        if (!synthetic.matches()) {
            return rawGroup;
        }
        if (singleSourceMesh) {
            return warpName(modelName, labels);
        }
        return deformerName(modelName, labels, Integer.parseInt(synthetic.group(1)));
    }

    /** Localizes synthetic fallback joint labels while preserving authored rig bone names. */
    static String bodyPartName(Map<String, String> labels, String rawBodyPart) {
        if (rawBodyPart == null || rawBodyPart.isEmpty() || !labels.containsKey(KEY_JOINT)) {
            return rawBodyPart;
        }
        Matcher synthetic = SYNTHETIC_JOINT_PART.matcher(rawBodyPart);
        if (!synthetic.matches()) {
            return rawBodyPart;
        }
        return formatTemplate(labels.getOrDefault(KEY_JOINT, FALLBACK_JOINT), synthetic.group(1));
    }

    /** Replaces bundled preset ASCII identifiers with localized deformer and mesh labels. */
    static ViewerScene localizePresetScene(ViewerScene scene, String presetId, SurfaceImport.Request request) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(request, "request");
        String presetGroupPrefix = presetId + " [";
        String localizedWarp = request.warpName();
        List<ViewerScene.Mesh> localizedMeshes = scene.meshes().stream()
                .map(mesh -> {
                    String meshName = presetId.equals(mesh.name()) ? "" : mesh.name();
                    String groupName = mesh.appearanceGroup() != null
                                    && (mesh.appearanceGroup().equals(presetId)
                                            || mesh.appearanceGroup().startsWith(presetGroupPrefix))
                            ? localizedWarp
                            : mesh.appearanceGroup();
                    return new ViewerScene.Mesh(
                            meshName,
                            mesh.positions(),
                            mesh.normals(),
                            mesh.uvs(),
                            mesh.emissiveUvs(),
                            mesh.colors(),
                            mesh.indices(),
                            mesh.materialIndex(),
                            mesh.visible(),
                            groupName,
                            mesh.bodyPart(),
                            mesh.controlSamples(),
                            mesh.opacityControls());
                })
                .toList();
        return new ViewerScene(
                localizedMeshes, scene.materials(), scene.textures(), scene.bounds(), scene.stats(), scene.controls());
    }
}
