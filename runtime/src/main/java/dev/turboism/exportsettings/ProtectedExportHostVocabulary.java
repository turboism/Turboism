package dev.turboism.exportsettings;

/**
 * The exact {@code cubism.protected-export.*} alias vocabulary the protected-export host
 * surface resolves through {@link dev.turboism.mapping.verification.VerifiedMemberResolver}.
 *
 * <p>Keeping the literals in one constant-only class makes the reviewed evidence traceable:
 * the roster in {@link VerifiedProtectedExportHostOperations} composes these constants, and no
 * protected-export class may invoke an alias that is not declared here. Package-private by
 * design — this is a vocabulary, never a behaviour seam, and it holds no host object.</p>
 */
final class ProtectedExportHostVocabulary {
    static final String APP_CONTROLLER_CLASS = "cubism.protected-export.app-controller.class";
    static final String APP_INSTANCE = "cubism.protected-export.app-controller.instance";
    static final String CURRENT_DOCUMENT = "cubism.protected-export.app-controller.current-document";
    static final String CURRENT_PROJECT = "cubism.protected-export.app-controller.current-project";
    static final String COMMAND_OPEN = "cubism.protected-export.app-controller.open";
    static final String COMMAND_CLOSE = "cubism.protected-export.app-controller.close-content";
    static final String MAIN_FRAME_CTRL = "cubism.protected-export.app-controller.main-frame-ctrl";
    static final String MAIN_FRAME = "cubism.protected-export.main-frame-ctrl.main-frame";
    static final String MAIN_FRAME_CTRL_CLASS = "cubism.protected-export.main-frame-ctrl.class";
    static final String PROJECT_CLASS = "cubism.protected-export.project.class";
    static final String PROJECT_CHILDREN = "cubism.protected-export.project.children";

    static final String DOCUMENT_CLASS = "cubism.protected-export.document.class";
    static final String DOC_MODEL_SOURCE = "cubism.protected-export.document.model-source";
    static final String DOC_FILE_CONTENT = "cubism.protected-export.document.file-content";
    static final String DOC_SELECTOR = "cubism.protected-export.document.selector";
    static final String DOC_EDIT_MODE_CURRENT = "cubism.protected-export.document.edit-mode-current";
    static final String DOC_EDIT_MODE_MAIN = "cubism.protected-export.document.edit-mode-main";
    static final String DOC_MARK_SAVED = "cubism.protected-export.document.mark-saved";
    static final String DOC_FILE = "cubism.protected-export.document.file";
    static final String DOC_UNDO = "cubism.protected-export.document.undo-manager";

    static final String FILE_CONTENT_CLASS = "cubism.protected-export.file-content.class";
    static final String FILE_CONTENT_FILE = "cubism.protected-export.file-content.file";
    static final String FILE_CONTENT_MODIFIED = "cubism.protected-export.file-content.modified";

    static final String UNDO_CLASS = "cubism.protected-export.undo-manager.class";
    static final String UNDO_POSITION = "cubism.protected-export.undo-manager.position";
    static final String UNDO_EDIT_COUNT = "cubism.protected-export.undo-manager.edit-count";
    static final String UNDO_CAN_UNDO = "cubism.protected-export.undo-manager.can-undo";

    static final String SELECTOR_INTERFACE = "cubism.protected-export.selector-interface.class";
    static final String SELECTOR_CLASS = "cubism.protected-export.selector.class";
    static final String SELECTOR_CLEAR = "cubism.protected-export.selector.clear";
    static final String SELECTOR_SELECTED = "cubism.protected-export.selector.selected";
    static final String SELECTOR_SELECTED_COUNT = "cubism.protected-export.selector.selected-count";
    static final String SELECTOR_ADD_SOURCE = "cubism.protected-export.selector.add-source";
    static final String SELECTOR_SELECTED_DEFORMERS = "cubism.protected-export.selector.selected-deformers";

    static final String EDIT_MODE_BASE_CLASS = "cubism.protected-export.edit-mode-base.class";
    static final String EDIT_MODE_CLASS = "cubism.protected-export.edit-mode.class";
    static final String EDIT_MODE_APPLY = "cubism.protected-export.edit-mode.apply-deformer";

    static final String MODEL_SOURCE_CLASS = "cubism.protected-export.model-source.class";
    static final String MODEL_CLASS = "cubism.protected-export.model.class";
    static final String MODEL_PARAMETER_SET = "cubism.protected-export.model.parameter-set";
    static final String MS_DOCUMENT = "cubism.protected-export.model-source.document";
    static final String MS_INSTANCE = "cubism.protected-export.model-source.current-instance";
    static final String MS_DEFORMERS = "cubism.protected-export.model-source.all-deformers";
    static final String MS_OBJECTS = "cubism.protected-export.model-source.all-objects";
    static final String MS_ART_MESHES = "cubism.protected-export.model-source.all-art-meshes";
    static final String MS_PARTS = "cubism.protected-export.model-source.all-parts";
    static final String MS_ROOT_PART = "cubism.protected-export.model-source.root-part";
    static final String MS_SAVE_MODEL = "cubism.protected-export.model-source.save-model";
    static final String MS_PARAMETERS = "cubism.protected-export.model-source.all-parameters";
    static final String MS_PHYSICS = "cubism.protected-export.model-source.all-physics-settings";
    static final String MS_MOTION_SYNC = "cubism.protected-export.model-source.all-motion-sync-settings";
    static final String MS_GUID = "cubism.protected-export.model-source.guid";
    static final String MS_CONTAIN_MULTIPLY = "cubism.protected-export.model-source.contain-multiply-color";
    static final String MS_CONTAIN_SCREEN = "cubism.protected-export.model-source.contain-screen-color";
    static final String MS_CONTAIN_MORPH = "cubism.protected-export.model-source.contain-morph-target";
    static final String MS_CONTAIN_MORPH_ENH = "cubism.protected-export.model-source.contain-morph-target-enhancement";
    static final String MS_CONTAIN_ADVANCED_BLEND = "cubism.protected-export.model-source.contain-advanced-blend";
    static final String MS_CONTAIN_ART_PATH = "cubism.protected-export.model-source.contain-art-path";
    static final String MS_CONTAIN_ALIAS = "cubism.protected-export.model-source.contain-alias";
    static final String MS_CONTAIN_INVERT_CLIP = "cubism.protected-export.model-source.contain-invert-clipping";
    static final String MS_CONTAIN_QUAD = "cubism.protected-export.model-source.contain-quad-transform";
    static final String MS_CONTAIN_OFFSCREEN = "cubism.protected-export.model-source.contain-offscreen-rendering";
    static final String MS_CONTAIN_MOTION_SYNC = "cubism.protected-export.model-source.contain-motion-sync";
    static final String MS_CONTAIN_MOTION_SYNC_FIX =
            "cubism.protected-export.model-source.contain-motion-sync-correction";

    static final String SOURCE_MORPH_SET = "cubism.protected-export.source.keyform-morph-target-set";
    static final String SOURCE_EXT_MORPH_SET = "cubism.protected-export.source.extended-morph-target-set";
    static final String SOURCE_EXTENSIONS = "cubism.protected-export.source.extensions";
    static final String MORPH_SET_CLASS = "cubism.protected-export.morph-target-set.class";
    static final String MORPH_SET_TARGETS = "cubism.protected-export.morph-target-set.morph-targets";

    static final String SOURCE_GUID = "cubism.protected-export.source.guid";
    static final String SOURCE_ID = "cubism.protected-export.source.id";
    static final String SOURCE_LOCAL_NAME = "cubism.protected-export.source.local-name";
    static final String SOURCE_SET_LOCAL_NAME = "cubism.protected-export.source.set-local-name";

    static final String DEFORMER_CLASS = "cubism.protected-export.deformer-source.class";
    static final String DEFORMER_GUID = "cubism.protected-export.deformer.guid";
    static final String DEFORMER_TARGET = "cubism.protected-export.deformer.target-guid";
    static final String DEFORMER_CHILDREN = "cubism.protected-export.deformer-source.children";
    static final String WARP_CLASS = "cubism.protected-export.warp-deformer.class";
    static final String ROTATION_CLASS = "cubism.protected-export.rotation-deformer.class";
    static final String ART_MESH_CLASS = "cubism.protected-export.art-mesh.class";
    static final String PART_CLASS = "cubism.protected-export.part.class";
    static final String PARAMETER_CLASS = "cubism.protected-export.parameter.class";
    static final String DRAWABLE_CLASS = "cubism.protected-export.drawable-source.class";
    static final String DRAWABLE_ID_GET = "cubism.protected-export.drawable.id";
    static final String DRAWABLE_ID_SET = "cubism.protected-export.drawable.set-id";
    static final String DRAWABLE_ID_CLASS = "cubism.protected-export.drawable-id.class";
    static final String DRAWABLE_ID_CREATE = "cubism.protected-export.drawable-id.create";
    static final String PARAMETER_ID_CLASS = "cubism.protected-export.parameter-id.class";
    static final String PARAMETER_SOURCE_ID = "cubism.protected-export.parameter-source.id";
    static final String PARAMETER_SOURCE_NAME = "cubism.protected-export.parameter-source.name";
    static final String PARAMETER_SOURCE_MIN = "cubism.protected-export.parameter-source.min-value";
    static final String PARAMETER_SOURCE_MAX = "cubism.protected-export.parameter-source.max-value";
    static final String PARAMETER_SOURCE_DEFAULT = "cubism.protected-export.parameter-source.default-value";
    static final String PARAMETER_SOURCE_REPEAT = "cubism.protected-export.parameter-source.repeat";
    static final String GUID_CLASS = "cubism.protected-export.guid.class";
    static final String GUID_UUID = "cubism.protected-export.guid.uuid-string";
    static final String ID_CLASS = "cubism.protected-export.id.class";
    static final String ID_STRING = "cubism.protected-export.id.id-string";

    static final String PARAMETER_SET_CLASS = "cubism.protected-export.parameter-set.class";
    static final String PARAMETER_SET_PARAMETERS = "cubism.protected-export.parameter-set.parameters";
    static final String PARAMETER_INSTANCE_CLASS = "cubism.protected-export.parameter-instance.class";
    static final String PARAMETER_INSTANCE_VALUE = "cubism.protected-export.parameter-instance.value";
    static final String PARAMETER_INSTANCE_ID = "cubism.protected-export.parameter-instance.id";
    static final String PARAMETER_INSTANCE_SET_VALUE = "cubism.protected-export.parameter-instance.set-value";

    static final String MODEL_REINIT_EXE = "cubism.protected-export.model.reinit-instance-exe";
    static final String MODEL_ART_MESHES = "cubism.protected-export.model.all-art-meshes";
    static final String ART_MESH_INSTANCE_CLASS = "cubism.protected-export.art-mesh-instance.class";
    static final String ART_MESH_INSTANCE_SOURCE = "cubism.protected-export.art-mesh-instance.source";
    static final String ART_MESH_INSTANCE_FORM = "cubism.protected-export.art-mesh-instance.calculated-form";
    static final String ART_MESH_FORM_CLASS = "cubism.protected-export.art-mesh-form.class";
    static final String ART_MESH_FORM_POSITIONS = "cubism.protected-export.art-mesh-form.positions";

    static final String GRID_CLASS = "cubism.protected-export.keyform-grid.class";
    static final String BINDING_CLASS = "cubism.protected-export.keyform-binding.class";
    static final String EXT_TYPE_CLASS = "cubism.protected-export.extended-interpolation-type.class";
    static final String SOURCE_GRID = "cubism.protected-export.source.keyform-grid";
    static final String SOURCE_EXT_GRID = "cubism.protected-export.source.extended-keyform-grid";
    static final String GRID_BINDINGS = "cubism.protected-export.keyform-grid.bindings";
    static final String BINDING_EXT_TYPE = "cubism.protected-export.keyform-binding.extended-type";
    static final String BINDING_ILLEGAL = "cubism.protected-export.keyform-binding.illegal-extended";
    static final String BINDING_PARAMETER_ID = "cubism.protected-export.keyform-binding.parameter-id";
    static final String BINDING_KEYS = "cubism.protected-export.keyform-binding.keys";
    static final String PART_CHILD_GUIDS = "cubism.protected-export.part.child-guids";

    static final String GLUE_CLASS = "cubism.protected-export.glue-source.class";
    static final String GLUE_TARGET_A = "cubism.protected-export.glue-source.target-art-mesh-a";
    static final String GLUE_TARGET_B = "cubism.protected-export.glue-source.target-art-mesh-b";

    static final String CONTROLLABLE_CLASS = "cubism.protected-export.controllable-source.class";
    static final String SOURCE_TARGET_DEFORMER = "cubism.protected-export.source.target-deformer-guid";
    static final String DRAWABLE_CLIP_GUIDS = "cubism.protected-export.drawable.clip-guids";
    static final String DRAWABLE_INVERT_CLIP = "cubism.protected-export.drawable.invert-clipping";
    static final String ART_PATH_CLASS = "cubism.protected-export.art-path-source.class";
    static final String ART_PATH_BRUSH = "cubism.protected-export.art-path.brush-guid";
    static final String ALIAS_CLASS = "cubism.protected-export.alias-source.class";
    static final String ALIAS_REFERENCE = "cubism.protected-export.alias.reference-object-guid";
    static final String ALIAS_CLIP_GUIDS = "cubism.protected-export.alias.clip-guids";
    static final String ALIAS_INVERT_CLIP = "cubism.protected-export.alias.invert-clipping";
    static final String ALIAS_USE_OFFSCREEN = "cubism.protected-export.alias.use-offscreen";
    static final String ALIAS_COLOR_COMPOSITION = "cubism.protected-export.alias.color-composition";
    static final String ALIAS_ALPHA_COMPOSITION = "cubism.protected-export.alias.alpha-composition";
    static final String ALIAS_CIRCULATED = "cubism.protected-export.alias.circulated";
    static final String PART_CLIP_GUIDS = "cubism.protected-export.part.clip-guids";
    static final String PART_INVERT_CLIP = "cubism.protected-export.part.invert-clipping";
    static final String PART_USE_OFFSCREEN = "cubism.protected-export.part.use-offscreen";
    static final String PART_COLOR_COMPOSITION = "cubism.protected-export.part.color-composition";
    static final String PART_ALPHA_COMPOSITION = "cubism.protected-export.part.alpha-composition";
    static final String PHYSICS_SETTINGS_CLASS = "cubism.protected-export.physics-settings-source.class";
    static final String PHYSICS_SETTINGS_GUID = "cubism.protected-export.physics-settings.guid";
    static final String PHYSICS_SETTINGS_ID = "cubism.protected-export.physics-settings.id";
    static final String PHYSICS_SETTINGS_NAME = "cubism.protected-export.physics-settings.name";
    static final String PHYSICS_SETTINGS_SET_NAME = "cubism.protected-export.physics-settings.set-name";
    static final String PHYSICS_SETTINGS_SET_ID = "cubism.protected-export.physics-settings.set-id";
    static final String PHYSICS_SETTING_ID_CLASS = "cubism.protected-export.physics-setting-id.class";
    static final String PHYSICS_SETTING_ID_CREATE = "cubism.protected-export.physics-setting-id.create";
    static final String PHYSICS_SETTINGS_ENABLE = "cubism.protected-export.physics-settings.enable";
    static final String PHYSICS_SETTINGS_INPUTS = "cubism.protected-export.physics-settings.inputs";
    static final String PHYSICS_SETTINGS_OUTPUTS = "cubism.protected-export.physics-settings.outputs";
    static final String PHYSICS_SETTINGS_VERTICES = "cubism.protected-export.physics-settings.vertices";
    static final String PHYSICS_SETTINGS_TOTAL_ANGLE = "cubism.protected-export.physics-settings.total-angle";
    static final String PHYSICS_SETTINGS_NORM_POS_MIN =
            "cubism.protected-export.physics-settings.normalization-position-min";
    static final String PHYSICS_SETTINGS_NORM_POS_DEFAULT =
            "cubism.protected-export.physics-settings.normalization-position-default";
    static final String PHYSICS_SETTINGS_NORM_POS_MAX =
            "cubism.protected-export.physics-settings.normalization-position-max";
    static final String PHYSICS_SETTINGS_NORM_ANGLE_MIN =
            "cubism.protected-export.physics-settings.normalization-angle-min";
    static final String PHYSICS_SETTINGS_NORM_ANGLE_DEFAULT =
            "cubism.protected-export.physics-settings.normalization-angle-default";
    static final String PHYSICS_SETTINGS_NORM_ANGLE_MAX =
            "cubism.protected-export.physics-settings.normalization-angle-max";
    static final String PHYSICS_INPUT_SOURCE = "cubism.protected-export.physics-input.source";
    static final String PHYSICS_INPUT_WEIGHT = "cubism.protected-export.physics-input.weight";
    static final String PHYSICS_INPUT_TYPE = "cubism.protected-export.physics-input.type";
    static final String PHYSICS_INPUT_REVERSE = "cubism.protected-export.physics-input.reverse";
    static final String PHYSICS_INPUT_ANGLE_SCALE = "cubism.protected-export.physics-input.angle-scale";
    static final String PHYSICS_INPUT_TRANSLATION_SCALE = "cubism.protected-export.physics-input.translation-scale";
    static final String PHYSICS_OUTPUT_DESTINATION = "cubism.protected-export.physics-output.destination";
    static final String PHYSICS_OUTPUT_VERTEX_INDEX = "cubism.protected-export.physics-output.vertex-index";
    static final String PHYSICS_OUTPUT_WEIGHT = "cubism.protected-export.physics-output.weight";
    static final String PHYSICS_OUTPUT_TYPE = "cubism.protected-export.physics-output.type";
    static final String PHYSICS_OUTPUT_REVERSE = "cubism.protected-export.physics-output.reverse";
    static final String PHYSICS_OUTPUT_ANGLE_SCALE = "cubism.protected-export.physics-output.angle-scale";
    static final String PHYSICS_OUTPUT_TRANSLATION_SCALE = "cubism.protected-export.physics-output.translation-scale";
    static final String PHYSICS_OUTPUT_BELOW_MINIMUM = "cubism.protected-export.physics-output.value-below-minimum";
    static final String PHYSICS_OUTPUT_EXCEEDED_MAXIMUM =
            "cubism.protected-export.physics-output.value-exceeded-maximum";
    static final String PHYSICS_VERTEX_POSITION = "cubism.protected-export.physics-vertex.position";
    static final String PHYSICS_VERTEX_MOBILITY = "cubism.protected-export.physics-vertex.mobility";
    static final String PHYSICS_VERTEX_DELAY = "cubism.protected-export.physics-vertex.delay";
    static final String PHYSICS_VERTEX_ACCELERATION = "cubism.protected-export.physics-vertex.acceleration";
    static final String PHYSICS_VERTEX_RADIUS = "cubism.protected-export.physics-vertex.radius";
    static final String VECTOR2_X = "cubism.protected-export.vector2.x";
    static final String VECTOR2_Y = "cubism.protected-export.vector2.y";
    static final String PARAMETER_SOURCE_GUID = "cubism.protected-export.parameter-source.guid";
    static final String MS_PHYSICS_SETTINGS_SET = "cubism.protected-export.model-source.physics-settings-set";
    static final String PHYSICS_SET_GRAVITY = "cubism.protected-export.physics-settings-set.gravity";
    static final String PHYSICS_SET_WIND = "cubism.protected-export.physics-settings-set.wind";
    static final String PHYSICS_SET_FPS = "cubism.protected-export.physics-settings-set.fps";
    static final String PHYSICS_SET_SELECTED = "cubism.protected-export.physics-settings-set.selected";
    static final String MOTION_SYNC_CLASS = "cubism.protected-export.motion-sync-setting.class";
    static final String MOTION_SYNC_GUID = "cubism.protected-export.motion-sync-setting.guid";
    static final String MOTION_SYNC_ID = "cubism.protected-export.motion-sync-setting.id";
    static final String MOTION_SYNC_ID_FIELD = "cubism.protected-export.motion-sync-setting.id-field";
    static final String MOTION_SYNC_NAME = "cubism.protected-export.motion-sync-setting.name";
    static final String MOTION_SYNC_SET_NAME = "cubism.protected-export.motion-sync-setting.set-name";
    static final String MOTION_SYNC_ID_CLASS = "cubism.protected-export.motion-sync-setting-id.class";
    static final String MOTION_SYNC_ID_CREATE = "cubism.protected-export.motion-sync-setting-id.create";
    static final String MOTION_SYNC_MAPPING = "cubism.protected-export.motion-sync-setting.mapping";
    static final String MOTION_SYNC_POSTPROC = "cubism.protected-export.motion-sync-setting.postproc";
    static final String MOTION_SYNC_VERSION = "cubism.protected-export.motion-sync-setting.version";
    static final String MOTION_SYNC_MAPPING_CHECKSUM = "cubism.protected-export.motion-sync-mapping.checksum";
    static final String MOTION_SYNC_POSTPROC_CHECKSUM = "cubism.protected-export.motion-sync-postproc.checksum";

    static final String DIALOG_CLASS = "cubism.protected-export.export-dialog.class";
    static final String DIALOG_MODEL_SOURCE = "cubism.protected-export.export-dialog.model-source";
    static final String DRIVER_CLASS = "cubism.protected-export.export-driver.class";
    static final String DRIVER_INSTANCE = "cubism.protected-export.export-driver.instance";
    static final String DRIVER_EXPORT = "cubism.protected-export.export-driver.export";

    static final String FILE_CACHE_CLASS = "cubism.protected-export.file-cache.class";
    static final String FILE_CACHE_INSTANCE = "cubism.protected-export.file-cache.instance";
    static final String FILE_CACHE_HANDLES = "cubism.protected-export.file-cache.handles";
    static final String FILE_CACHE_BY_FILE = "cubism.protected-export.file-cache.handle-by-file";
    static final String FILE_CACHE_REMOVE = "cubism.protected-export.file-cache.remove";
    static final String FILE_HANDLE_CLASS = "cubism.protected-export.file-handle.class";
    static final String FILE_HANDLE_FILE = "cubism.protected-export.file-handle.file";
    static final String FILE_HANDLE_LOADER = "cubism.protected-export.file-handle.loader";
    static final String FILE_HANDLE_LISTENERS = "cubism.protected-export.file-handle.listeners";
    static final String FILE_HANDLE_UNLOAD = "cubism.protected-export.file-handle.unload";
    static final String FILE_HANDLE_RELEASE = "cubism.protected-export.file-handle.release";
}
