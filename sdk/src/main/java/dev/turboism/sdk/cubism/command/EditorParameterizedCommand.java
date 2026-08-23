package dev.turboism.sdk.cubism.command;


import java.util.Set;

/** Closed inventory of typed operations awaiting or using command-specific request records. */
public enum EditorParameterizedCommand {
    ADD_PARAMETER_FOR_ROTATION_DEFORMER(Set.of("5.2.03", "5.3.02")),
    ADD_TIMELINE_MARKER(Set.of("5.2.03", "5.3.02")),
    ART_PATH_BRUSH_DIALOG(Set.of("5.2.03", "5.3.02")),
    AUTO_BLEND_4_CORNER(Set.of("5.2.03", "5.3.02")),
    AUTO_CREATE_DEFORMERS(Set.of("5.2.03", "5.3.02")),
    AUTO_CREATE_FACE_DEFORMER(Set.of("5.2.03", "5.3.02")),
    AUTO_MOVE_FACE_DEFORMER(Set.of("5.2.03", "5.3.02")),
    BAKE_MOTION_SYNC(Set.of("5.2.03", "5.3.02")),
    BAKE_PHYSICS(Set.of("5.2.03", "5.3.02")),
    BLEND_SHAPE(Set.of("5.2.03", "5.3.02")),
    CHANGE_ALL_MESH_DRAW_ORDER(Set.of("5.2.03", "5.3.02")),
    CHANGE_MESH_DRAW_ORDER(Set.of("5.2.03", "5.3.02")),
    COLLECTION(Set.of("5.2.03", "5.3.02")),
    CONVERT_ID_MODEL(Set.of("5.2.03", "5.3.02")),
    CREATE_ROTATION_DEFORMER(Set.of("5.2.03", "5.3.02")),
    CREATE_SCENE_FROM_SOUNDS(Set.of("5.2.03", "5.3.02")),
    CREATE_WARP_DEFORMER(Set.of("5.2.03", "5.3.02")),
    CREATE_YURE(Set.of("5.2.03", "5.3.02")),
    DISPLAY_MULTI_PASTE_SHAPE_DIALOG(Set.of("5.2.03", "5.3.02")),
    EDIT_EXTENSION_INTERPOLATION(Set.of("5.2.03", "5.3.02")),
    EDIT_MULTIKEY(Set.of("5.2.03", "5.3.02")),
    EDIT_MULTIKEY_FOR_ARTPATH(Set.of("5.2.03", "5.3.02")),
    EDIT_REVERSE_ALL_KEYS(Set.of("5.2.03", "5.3.02")),
    EXPORT_MODEL_AS_PNG(Set.of("5.2.03", "5.3.02")),
    EXPORT_MODEL_AS_PSD(Set.of("5.2.03", "5.3.02")),
    EXPORT_SCENE_AS_ANIMATED_GIF(Set.of("5.2.03", "5.3.02")),
    EXPORT_SCENE_AS_PNG(Set.of("5.2.03", "5.3.02")),
    EXPORT_SCENE_AS_VIDEO(Set.of("5.2.03", "5.3.02")),
    EXTERNAL_APP_SETTING(Set.of("5.2.03", "5.3.02")),
    FADE_SETTING(Set.of("5.3.02")),
    FIND_OBJECT_DIALOG(Set.of("5.2.03", "5.3.02")),
    GENERATE_MESH_IN_MESH_EDITOR_MODE(Set.of("5.2.03", "5.3.02")),
    GENERATE_MESH_IN_MODELING_MODE(Set.of("5.2.03", "5.3.02")),
    GRID_SETTING(Set.of("5.2.03", "5.3.02")),
    INVERSION_OF_MOVEMENT(Set.of("5.2.03", "5.3.02")),
    KEYBOARD_SHORTCUT_SETTING(Set.of("5.2.03", "5.3.02")),
    LIPSYNC(Set.of("5.2.03", "5.3.02")),
    MODELING_REFLECTION(Set.of("5.2.03", "5.3.02")),
    MODELING_STATISTICS(Set.of("5.2.03", "5.3.02")),
    MODEL_SETTING(Set.of("5.2.03", "5.3.02")),
    NEW_ANIMATION(Set.of("5.2.03", "5.3.02")),
    OPEN_LINE_INFO_PICK_DIALOG(Set.of("5.2.03", "5.3.02")),
    OPEN_MOTIONPATH_SETTING(Set.of("5.2.03", "5.3.02")),
    OPEN_ONIONSKIN_SETTING(Set.of("5.2.03", "5.3.02")),
    OPEN_PARAMETER_BOOKMARK_SETTING(Set.of("5.2.03", "5.3.02")),
    OPEN_PARAMETER_GROUP_SETTING(Set.of("5.2.03", "5.3.02")),
    OPEN_PARAMETER_SETTING(Set.of("5.2.03", "5.3.02")),
    OPEN_PASTE_BLEND_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    PARAM_CTRL_CALC_ORDER_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    REFLECT_FADE(Set.of("5.3.02")),
    RESIZE_ARTMESH_WITH_SCALE(Set.of("5.2.03", "5.3.02")),
    RESIZE_MODEL_DOCUMENT(Set.of("5.2.03", "5.3.02")),
    SETTING_GUIDES_MODELING_SETTING(Set.of("5.2.03", "5.3.02")),
    SHOW_ANIMATION_PREVIEW_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    SHOW_CONFIG_DIALOG(Set.of("5.2.03", "5.3.02")),
    SHOW_EFFECT_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    SHOW_LABEL_COPY_DIALOG(Set.of("5.3.02")),
    SHOW_MORPH_TARGET_CONSTRAINT_EDIT_DIALOG(Set.of("5.2.03", "5.3.02")),
    SHOW_MOTION_SYNC_SETTING_DAILOG(Set.of("5.2.03", "5.3.02")),
    SHOW_ROTATE3D_SETTING_DIALOG(Set.of("5.2.03", "5.3.02")),
    SHOW_SHOW_LABEL_COPY_DIALOG(Set.of("5.2.03")),
    SHOW_VIEWER(Set.of("5.2.03", "5.3.02")),
    TEXTURE_ATLAS(Set.of("5.2.03", "5.3.02")),
    VALIDATE_DEFORMER_DIALOG(Set.of("5.2.03", "5.3.02")),
    VALIDATE_PARAMETER_MAPPING_DIALOG(Set.of("5.2.03", "5.3.02")),
    WORKSPACE_SETTING(Set.of("5.2.03", "5.3.02"));

    private final Set<String> supportedVersions;

    public enum Availability {
        EVIDENCE_REQUIRED,
        TYPED_CONTRACT_VERIFIED
    }

    EditorParameterizedCommand(final Set<String> supportedVersions) {
        this.supportedVersions = Set.copyOf(supportedVersions);
    }

    /**
     * @return the host command identifier: the constant name lowercased and dot-separated,
     *     computed with {@code Locale.ROOT} so it does not shift with the default locale
     */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
    }

    /**
     * @return {@link Availability#TYPED_CONTRACT_VERIFIED} for the handful of commands that already
     *     have a verified request record behind them, and {@link Availability#EVIDENCE_REQUIRED}
     *     for every other constant — those are inventoried but not yet callable with a typed
     *     request
     */
    public Availability availability() {
        return switch (this) {
            case EXTERNAL_APP_SETTING, GRID_SETTING, MODEL_SETTING, MODELING_STATISTICS, RESIZE_MODEL_DOCUMENT -> Availability.TYPED_CONTRACT_VERIFIED;
            default -> Availability.EVIDENCE_REQUIRED;
        };
    }

    /**
     * @param cubismVersion an exact Editor version string such as {@code "5.2.03"}; compared for
     *     equality, never parsed as a range
     * @return whether this command was observed on that version; a few constants are
     *     version-exclusive (for example {@code FADE_SETTING} on 5.3.02 only)
     */
    public boolean supports(final String cubismVersion) {
        return supportedVersions.contains(cubismVersion);
    }
}
