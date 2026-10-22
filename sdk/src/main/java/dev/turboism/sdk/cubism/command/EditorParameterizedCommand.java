package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.ReviewedVersions;
import dev.turboism.sdk.CubismEditor;
import java.util.Set;

/** Closed inventory of typed operations awaiting or using command-specific request records. */
@CubismEditor
public enum EditorParameterizedCommand {
    ADD_PARAMETER_FOR_ROTATION_DEFORMER(ReviewedVersions.since("5.0.00")),
    ADD_TIMELINE_MARKER(ReviewedVersions.since("5.0.00")),
    ART_PATH_BRUSH_DIALOG(ReviewedVersions.since("5.0.00")),
    AUTO_BLEND_4_CORNER(ReviewedVersions.since("5.0.00")),
    AUTO_CREATE_DEFORMERS(ReviewedVersions.since("5.1.00")),
    AUTO_CREATE_FACE_DEFORMER(ReviewedVersions.since("5.0.00")),
    AUTO_MOVE_FACE_DEFORMER(ReviewedVersions.since("5.0.00")),
    BAKE_MOTION_SYNC(ReviewedVersions.since("5.0.00")),
    BAKE_PHYSICS(ReviewedVersions.since("5.0.00")),
    BLEND_SHAPE(ReviewedVersions.since("5.0.00")),
    CHANGE_ALL_MESH_DRAW_ORDER(ReviewedVersions.since("5.2.03")),
    CHANGE_MESH_DRAW_ORDER(ReviewedVersions.since("5.2.03")),
    COLLECTION(ReviewedVersions.since("5.0.00")),
    CONVERT_ID_MODEL(ReviewedVersions.since("5.0.00")),
    CREATE_ROTATION_DEFORMER(ReviewedVersions.since("5.0.00")),
    CREATE_SCENE_FROM_SOUNDS(ReviewedVersions.since("5.0.00")),
    CREATE_WARP_DEFORMER(ReviewedVersions.since("5.0.00")),
    CREATE_YURE(ReviewedVersions.since("5.1.00")),
    DISPLAY_MULTI_PASTE_SHAPE_DIALOG(
            ReviewedVersions.since("5.2.00")),
    EDIT_EXTENSION_INTERPOLATION(ReviewedVersions.since("5.0.00")),
    EDIT_MULTIKEY(ReviewedVersions.since("5.0.00")),
    EDIT_MULTIKEY_FOR_ARTPATH(ReviewedVersions.since("5.0.00")),
    EDIT_REVERSE_ALL_KEYS(ReviewedVersions.since("5.0.00")),
    EXPORT_MODEL_AS_PNG(ReviewedVersions.since("5.0.00")),
    EXPORT_MODEL_AS_PSD(ReviewedVersions.since("5.0.00")),
    EXPORT_SCENE_AS_ANIMATED_GIF(ReviewedVersions.since("5.0.00")),
    EXPORT_SCENE_AS_PNG(ReviewedVersions.since("5.0.00")),
    EXPORT_SCENE_AS_VIDEO(ReviewedVersions.since("5.0.00")),
    EXTERNAL_APP_SETTING(ReviewedVersions.since("5.1.00")),
    FADE_SETTING(ReviewedVersions.line("5.3")),
    FIND_OBJECT_DIALOG(ReviewedVersions.since("5.0.00")),
    GENERATE_MESH_IN_MESH_EDITOR_MODE(ReviewedVersions.since("5.0.00")),
    GENERATE_MESH_IN_MODELING_MODE(ReviewedVersions.since("5.0.00")),
    GRID_SETTING(ReviewedVersions.since("5.0.00")),
    INVERSION_OF_MOVEMENT(ReviewedVersions.since("5.0.00")),
    KEYBOARD_SHORTCUT_SETTING(ReviewedVersions.since("5.0.00")),
    LIPSYNC(ReviewedVersions.since("5.0.00")),
    MODELING_REFLECTION(ReviewedVersions.since("5.0.00")),
    MODELING_STATISTICS(ReviewedVersions.since("5.0.00")),
    MODEL_SETTING(ReviewedVersions.since("5.0.00")),
    NEW_ANIMATION(ReviewedVersions.since("5.0.00")),
    OPEN_LINE_INFO_PICK_DIALOG(ReviewedVersions.since("5.0.00")),
    OPEN_MOTIONPATH_SETTING(ReviewedVersions.since("5.0.00")),
    OPEN_ONIONSKIN_SETTING(ReviewedVersions.since("5.0.00")),
    OPEN_PARAMETER_BOOKMARK_SETTING(ReviewedVersions.since("5.0.00")),
    OPEN_PARAMETER_GROUP_SETTING(ReviewedVersions.since("5.1.02")),
    OPEN_PARAMETER_SETTING(ReviewedVersions.since("5.0.00")),
    OPEN_PASTE_BLEND_SETTING_DIALOG(ReviewedVersions.since("5.0.00")),
    PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG(
            ReviewedVersions.since("5.2.00")),
    PARAM_CTRL_CALC_ORDER_SETTING_DIALOG(
            ReviewedVersions.since("5.2.00")),
    REFLECT_FADE(ReviewedVersions.line("5.3")),
    RESIZE_ARTMESH_WITH_SCALE(ReviewedVersions.since("5.0.00")),
    RESIZE_MODEL_DOCUMENT(ReviewedVersions.since("5.0.00")),
    SETTING_GUIDES_MODELING_SETTING(ReviewedVersions.since("5.0.00")),
    SHOW_ANIMATION_PREVIEW_SETTING_DIALOG(ReviewedVersions.since("5.0.00")),
    SHOW_CONFIG_DIALOG(ReviewedVersions.since("5.0.00")),
    SHOW_EFFECT_SETTING_DIALOG(ReviewedVersions.since("5.0.00")),
    // The 5.3 rename of SHOW_SHOW_LABEL_COPY_DIALOG: same label-copy dialog command, new id.
    SHOW_LABEL_COPY_DIALOG(ReviewedVersions.line("5.3")),
    SHOW_MORPH_TARGET_CONSTRAINT_EDIT_DIALOG(ReviewedVersions.since("5.0.00")),
    SHOW_MOTION_SYNC_SETTING_DAILOG(ReviewedVersions.since("5.0.00")),
    SHOW_ROTATE3D_SETTING_DIALOG(ReviewedVersions.since("5.1.00")),
    // The 5.0/5.1/5.2 id of the label-copy dialog command; renamed to SHOW_LABEL_COPY_DIALOG in 5.3.
    SHOW_SHOW_LABEL_COPY_DIALOG(ReviewedVersions.between("5.0.00", "5.2.03")),
    SHOW_VIEWER(ReviewedVersions.since("5.0.00")),
    TEXTURE_ATLAS(ReviewedVersions.since("5.0.00")),
    VALIDATE_DEFORMER_DIALOG(ReviewedVersions.since("5.0.00")),
    VALIDATE_PARAMETER_MAPPING_DIALOG(ReviewedVersions.since("5.0.00")),
    WORKSPACE_SETTING(ReviewedVersions.since("5.0.00"));

    private final Set<String> supportedVersions;

    /** Whether a command constant already has a verified typed request contract behind it. */
    @CubismEditor
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
            case EXTERNAL_APP_SETTING, GRID_SETTING, MODEL_SETTING, RESIZE_MODEL_DOCUMENT ->
                Availability.TYPED_CONTRACT_VERIFIED;
            default -> Availability.EVIDENCE_REQUIRED;
        };
    }

    /**
     * @param cubismVersion an exact Editor version string such as {@code "5.2.03"}; compared for
     *     equality, never parsed as a range
     * @return whether this command was observed on that version; a few constants are
     *     version-exclusive — for example {@code SHOW_LABEL_COPY_DIALOG} exists only on the
     *     5.3 line, where it renamed the 5.2-and-below {@code SHOW_SHOW_LABEL_COPY_DIALOG}
     */
    public boolean supports(final String cubismVersion) {
        return supportedVersions.contains(cubismVersion);
    }
}
