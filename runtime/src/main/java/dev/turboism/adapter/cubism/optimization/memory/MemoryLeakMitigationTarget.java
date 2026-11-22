package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes, members, and per-version obfuscation mappings for Cubism memory
 * and OpenGL resource leak mitigations across Cubism 5.0.00 through 5.3.05.
 */
public record MemoryLeakMitigationTarget(
        HostArtifactDigest digest,
        String version,
        String keyRegistryOwner,
        String renderingCacheOwner,
        String partsCloseMethod,
        List<StaticHolderSpec> staticHolders,
        List<String> targetClasses) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.memoryLeakMitigation";

    /** Target class table resource base name. */
    public static final String TARGET_TABLE_NAME = "memory-leak-mitigation";

    /** All reviewed Cubism versions supported by this mitigation contract. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    private static final Set<String> EARLY_KEY_REGISTRY_VERSIONS = ReviewedVersions.between("5.0.00", "5.0.02");
    private static final Set<String> MID_KEY_REGISTRY_VERSIONS = ReviewedVersions.between("5.0.03", "5.2.03");
    private static final Set<String> PRE_53_RENDERING_CACHE_VERSIONS = ReviewedVersions.between("5.0.00", "5.2.03");
    private static final Set<String> PARTS_LEGACY_CLOSE_VERSIONS = ReviewedVersions.between("5.0.00", "5.1.03");
    private static final Set<String> PARTS_MID_CLOSE_VERSIONS = ReviewedVersions.between("5.2.00", "5.2.02");
    private static final Set<String> PARAMETER_DIALOG_G_VERSIONS = ReviewedVersions.since("5.0.05");
    private static final Set<String> SCENE_UI_VERSIONS = ReviewedVersions.since("5.2.00");
    private static final Set<String> PARAMETER_DIALOG_AF_VERSIONS = ReviewedVersions.since("5.3.00");

    private static final Map<String, List<String>> REVIEWED_CLASSES = TargetClassTable.load(TARGET_TABLE_NAME);
    private static final Map<HostArtifactDigest, MemoryLeakMitigationTarget> BY_DIGEST = buildByDigest();
    private static final List<MemoryLeakMitigationTarget> ALL = List.copyOf(BY_DIGEST.values());

    // ---- Key listener registry owners ----
    public static final String KEY_REGISTRY_OWNER_50_EARLY = "com/live2d/util/d/m";
    public static final String KEY_REGISTRY_OWNER_50_TO_52 = "com/live2d/util/e/m";
    public static final String KEY_REGISTRY_OWNER_53_PLUS = "com/live2d/util/f/n";
    public static final String KEY_REGISTRY_MAP_FIELD = "e";
    public static final String KEY_REGISTRY_MAP_DESCRIPTOR = "Ljava/util/HashMap;";

    // ---- Rendering FBO cache owners ----
    public static final String RENDERING_CACHE_OWNER_PRE_53 = "com/live2d/graphics3d/rendering/a";
    public static final String RENDERING_CACHE_OWNER_53_PLUS = "com/live2d/graphics3d/rendering/b";
    public static final String RENDERING_CACHE_LOCK_FIELD = "b";
    public static final String RENDERING_CACHE_MAP_FIELD = "c";

    // ---- Priority 1: Parts palette & tree table ----
    public static final String PARTS_PANEL_OWNER = "com/live2d/cubism/view/palette/parts/b";
    public static final String PARTS_REBUILD_METHOD = "a";
    public static final String PARTS_UPDATE_METHOD = "b";
    public static final String PARTS_SET_TREE_METHOD = "a";
    public static final String PARTS_SET_TREE_DESCRIPTOR = "(Lcom/live2d/ui/treeTable/CTreeTable;)V";
    public static final String PARTS_BOOLEAN_PAIR_DESCRIPTOR = "(ZZ)V";
    public static final String PARTS_CLOSE_METHOD_LEGACY = "w";
    public static final String PARTS_CLOSE_METHOD_MID = "x";
    public static final String PARTS_CLOSE_METHOD_MODERN = "y";
    public static final String TREE_TABLE_OWNER = "com/live2d/ui/treeTable/CTreeTable";
    public static final String TREE_TABLE_FIELD = "b";
    public static final String UI_EVENT_HANDLER_OWNER = "com/live2d/ui/m";
    public static final String C_WIDGET_OWNER = "com/live2d/ui/CWidget";
    public static final String CVBOX_OWNER = "com/live2d/ui/container/CVBox";
    public static final String REMOVE_ALL_METHOD = "removeAll";

    // ---- Priority 2: GL VBO, entity, texture & offscreen context lifecycle ----
    public static final String GL_BUFFER_BASE_OWNER = "com/live2d/graphics3d/mesh/a/a";
    public static final String GL_BUFFER_FLOAT_OWNER = "com/live2d/graphics3d/mesh/a/b";
    public static final String GL_BUFFER_INT_OWNER = "com/live2d/graphics3d/mesh/a/c";
    public static final String GL_BUFFER_UPLOAD_METHOD = "b";
    public static final String GL_BUFFER_UPLOAD_DESCRIPTOR = "(Lcom/jogamp/opengl/GL2ES2;I)V";
    public static final String GL2ES2_OWNER = "com/jogamp/opengl/GL2ES2";
    public static final String GL_DELETE_BUFFERS_METHOD = "glDeleteBuffers";
    public static final String GL_GEN_BUFFERS_METHOD = "glGenBuffers";
    public static final String GL_BUFFERS_DESCRIPTOR = "(ILjava/nio/IntBuffer;)V";

    public static final String G_ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";
    public static final String ENTITIES_OWNER = "com/live2d/graphics3d/entity/Entities";
    public static final String EDITABLE_MESH_SEL_OWNER =
            "com/live2d/cubism/view/context/drawImpl/EditableMeshSelectionEntity";
    public static final String MESH_EDITOR_VC_OWNER = "com/live2d/cubism/view/context/CEViewContext_MeshEditor";
    public static final String MESH_EDITOR_EM_OWNER =
            "com/live2d/cubism/doc/modeling/CModelingEditMode_MeshEditor";
    public static final String G_MESH_RENDERER_OWNER = "com/live2d/graphics3d/component/GMeshRenderer";
    public static final String G_LINE_RENDERER_OWNER = "com/live2d/graphics3d/component/GLineRenderer";
    public static final String G_POINT_RENDERER_OWNER = "com/live2d/graphics3d/component/GPointRenderer";
    public static final String G_GROUP_RENDERER_OWNER = "com/live2d/graphics3d/component/GGroupRenderer";
    public static final String G_SIMPLE_LINES_ENTITY_OWNER =
            "com/live2d/graphics3d/geometry/GSimpleLinesEntity";

    public static final String G_TEXTURE_OWNER = "com/live2d/graphics3d/texture/GTexture";
    public static final String G_TEXTURE_2D_OWNER = "com/live2d/graphics3d/texture/GTexture2D";
    public static final String TEXTURE_SETUP_EXE_METHOD = "setup_exe";
    public static final String TEXTURE_SETUP_EXE_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";
    public static final String TEXTURE_RELEASE_GL_METHOD = "releaseGLResource_for41";
    public static final String TEXTURE_RELEASE_GL_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    public static final String GL_WRAPPER_OWNER = "com/live2d/graphics3d/view/glWrapper/a";
    public static final String GL_WRAPPER_LISTENER_OWNER = "com/live2d/graphics3d/view/glWrapper/a$b";
    public static final String GL_WRAPPER_DESTROY_METHOD = "c";
    public static final String GL_EVENT_DISPOSE_METHOD = "dispose";
    public static final String GL_EVENT_DISPOSE_DESCRIPTOR = "(Lcom/jogamp/opengl/GLAutoDrawable;)V";
    public static final String ENTITY_DISPOSE_METHOD = "dispose";
    public static final String SELECTION_RESET_METHOD = "reset";
    public static final String MESH_EDITOR_DEACTIVATE_METHOD = "deactivateView";
    public static final String MESH_EDITOR_END_METHOD = "endMode";

    // ---- Priority 3: Bounded undo history & document close ----
    public static final String UNDO_MANAGER_OWNER = "com/live2d/undo/CUndoManager";
    public static final String UNDO_CLEANUP_METHOD = "cleanup";
    public static final String MODELING_DOC_OWNER = "com/live2d/cubism/doc/modeling/CModelingDocument";
    public static final String CLOSE_FILE_METHOD = "closeFile";
    public static final String CLOSE_FILE_DESCRIPTOR = "(ZZ)Z";
    public static final String SCENE_DOC_OWNER = "com/live2d/cubism/doc/animation/CSceneDocument";
    public static final String CLOSE_DOCUMENT_METHOD = "closeDocument";

    // ---- Priority 4: CImageResource & CWritableImage ----
    public static final String IMAGE_RESOURCE_OWNER = "com/live2d/graphics/CImageResource";
    public static final String IMAGE_RESOURCE_COMPANION_OWNER = "com/live2d/graphics/CImageResource$a";
    public static final String RELEASE_RESOURCE_METHOD = "releaseResource";
    public static final String RELEASE_RESOURCE_DESCRIPTOR = "(Lcom/live2d/graphics/ICImageResourceUser;)V";
    public static final String RELEASED_DEBUG_FIELD = "releasedRetainUserData_forDebug";
    public static final String IMAGE_CLEANUP_METHOD = "f";
    public static final String ARCHIVE_IF_IDLE_METHOD = "archiveIfIdle";
    public static final String WRITABLE_IMAGE_OWNER = "com/live2d/graphics/CWritableImage";
    public static final String FLUSH_METHOD = "flush";

    // ---- Priority 5: Inspector, ViewContext & ParameterGroupRowWidget ----
    public static final String INSPECTOR_CTRL_OWNER = "com/live2d/cubism/view/palette/inspector/d";
    public static final String INSPECTOR_UPDATE_METHOD = "a";
    public static final String INSPECTOR_UPDATE_DESCRIPTOR = "(Ljava/util/List;)V";
    public static final String VIEW_CONTEXT_OWNER = "com/live2d/cubism/view/context/CEViewContext";
    public static final String CLOSE_VIEW_METHOD = "closeView";
    public static final String PARAM_GROUP_ROW_WIDGET_OWNER =
            "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget";
    public static final String RENAME_FOLDER_METHOD = "renameFolder";

    // ---- Common descriptors ----
    public static final String VOID_METHOD_DESCRIPTOR = "()V";
    public static final String BOOLEAN_GETTER_DESCRIPTOR = "()Z";

    // ---- Model / scene static holder specs ----
    public static final String MODEL_SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";
    public static final String SCENE_SOURCE_OWNER = "com/live2d/cubism/doc/animation/CSceneSource";

    private static final String PARTS_DIALOG_HOLDER = "com/live2d/cubism/view/palette/parts/a/a";
    private static final String APP_UI_HOLDER = "com/live2d/cubism/appCtrlImpl/ui/a/a";
    private static final String PARAM_DIALOG_G_HOLDER = "com/live2d/cubism/view/palette/parameter/dialog/G";
    private static final String PARAM_DIALOG_AF_HOLDER = "com/live2d/cubism/view/palette/parameter/dialog/af";
    private static final String ANIM_UI_HOLDER = "com/live2d/cubism/doc/animation/ui/a";
    private static final String TIMELINE_UTIL_HOLDER = "com/live2d/cubism/view/palette/timeline/Utils/v";

    /**
     * Static field cleared on document close when it holds the closing model or scene source.
     *
     * @param owner internal name of the class declaring the static field
     * @param field static field name
     * @param expectedTypeOwner expected internal name of the held source type
     * @param sceneSource true when the held value is a {@code CSceneSource}, false for {@code CModelSource}
     */
    public record StaticHolderSpec(String owner, String field, String expectedTypeOwner, boolean sceneSource) {}

    /** Creates a validated target contract for one reviewed Cubism artifact version. */
    public MemoryLeakMitigationTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(keyRegistryOwner, "keyRegistryOwner");
        Objects.requireNonNull(renderingCacheOwner, "renderingCacheOwner");
        Objects.requireNonNull(partsCloseMethod, "partsCloseMethod");
        staticHolders = List.copyOf(Objects.requireNonNull(staticHolders, "staticHolders"));
        targetClasses = List.copyOf(Objects.requireNonNull(targetClasses, "targetClasses"));
    }

    /** Resolves the reviewed mitigation target contract for a host artifact digest, or empty if unsupported. */
    public static Optional<MemoryLeakMitigationTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return Optional.ofNullable(BY_DIGEST.get(digest));
    }

    /** Returns every reviewed mitigation target in catalog order. */
    public static List<MemoryLeakMitigationTarget> all() {
        return ALL;
    }

    private static Map<HostArtifactDigest, MemoryLeakMitigationTarget> buildByDigest() {
        final Map<HostArtifactDigest, MemoryLeakMitigationTarget> targets = new java.util.LinkedHashMap<>();
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            final Optional<String> version = ReviewedHostArtifacts.cubismVersionOf(artifact)
                    .filter(SUPPORTED_VERSIONS::contains);
            if (version.isPresent()) {
                targets.put(artifact, createTarget(artifact, version.get()));
            }
        }
        return Map.copyOf(targets);
    }

    /** Returns the reviewed target-class map across all reviewed Cubism versions. */
    public static Map<String, List<String>> reviewedClasses() {
        return REVIEWED_CLASSES;
    }

    /** Returns the rewritten host class names for a reviewed target version. */
    public List<String> rewrittenOwners() {
        return List.of(
                PARTS_PANEL_OWNER,
                GL_BUFFER_FLOAT_OWNER,
                GL_BUFFER_INT_OWNER,
                G_ENTITY_OWNER,
                EDITABLE_MESH_SEL_OWNER,
                MESH_EDITOR_VC_OWNER,
                MESH_EDITOR_EM_OWNER,
                G_TEXTURE_OWNER,
                G_TEXTURE_2D_OWNER,
                GL_WRAPPER_OWNER,
                GL_WRAPPER_LISTENER_OWNER,
                UNDO_MANAGER_OWNER,
                MODELING_DOC_OWNER,
                SCENE_DOC_OWNER,
                IMAGE_RESOURCE_OWNER,
                IMAGE_RESOURCE_COMPANION_OWNER,
                WRITABLE_IMAGE_OWNER,
                INSPECTOR_CTRL_OWNER,
                VIEW_CONTEXT_OWNER,
                PARAM_GROUP_ROW_WIDGET_OWNER);
    }

    private static MemoryLeakMitigationTarget createTarget(final HostArtifactDigest digest, final String version) {
        final String keyRegistryOwner = EARLY_KEY_REGISTRY_VERSIONS.contains(version)
                ? KEY_REGISTRY_OWNER_50_EARLY
                : (MID_KEY_REGISTRY_VERSIONS.contains(version)
                        ? KEY_REGISTRY_OWNER_50_TO_52
                        : KEY_REGISTRY_OWNER_53_PLUS);
        final String renderingCacheOwner = PRE_53_RENDERING_CACHE_VERSIONS.contains(version)
                ? RENDERING_CACHE_OWNER_PRE_53
                : RENDERING_CACHE_OWNER_53_PLUS;
        final String partsCloseMethod = PARTS_LEGACY_CLOSE_VERSIONS.contains(version)
                ? PARTS_CLOSE_METHOD_LEGACY
                : (PARTS_MID_CLOSE_VERSIONS.contains(version) ? PARTS_CLOSE_METHOD_MID : PARTS_CLOSE_METHOD_MODERN);
        final List<StaticHolderSpec> holders = new java.util.ArrayList<>();
        holders.add(new StaticHolderSpec(PARTS_DIALOG_HOLDER, "d", MODEL_SOURCE_OWNER, false));
        holders.add(new StaticHolderSpec(APP_UI_HOLDER, "d", MODEL_SOURCE_OWNER, false));
        if (PARAMETER_DIALOG_G_VERSIONS.contains(version)) {
            holders.add(new StaticHolderSpec(PARAM_DIALOG_G_HOLDER, "d", MODEL_SOURCE_OWNER, false));
        }
        if (PARAMETER_DIALOG_AF_VERSIONS.contains(version)) {
            holders.add(new StaticHolderSpec(PARAM_DIALOG_AF_HOLDER, "i", MODEL_SOURCE_OWNER, false));
        }
        if (SCENE_UI_VERSIONS.contains(version)) {
            holders.add(new StaticHolderSpec(ANIM_UI_HOLDER, "u", SCENE_SOURCE_OWNER, true));
            holders.add(new StaticHolderSpec(TIMELINE_UTIL_HOLDER, "k", SCENE_SOURCE_OWNER, true));
        }
        final List<String> classes = TargetClassTable.classes(TARGET_TABLE_NAME, version);
        return new MemoryLeakMitigationTarget(
                digest, version, keyRegistryOwner, renderingCacheOwner, partsCloseMethod, holders, classes);
    }
}
