package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.cubism.ReviewedVersions;
import dev.turboism.sdk.CubismEditor;
import java.util.Set;

/** Safe no-argument semantic operations discovered from the normal Cubism menus. */
@CubismEditor
public enum EditorCommand {
    ADD_NEAR_CTRL_POINT_FOR_SELECTED_ART_MESH_COUNTER(ReviewedVersions.since("5.0.00")),
    ADD_SCENE(ReviewedVersions.since("5.0.00")),
    ADD_TAB_TABBOX(ReviewedVersions.since("5.0.00")),
    ADJUST_BOUNDING_BOX_ANGLE(ReviewedVersions.since("5.2.03")),
    ALL_OBJECT_SHOW(ReviewedVersions.since("5.0.00")),
    ALL_OBJECT_UNLOCK(ReviewedVersions.since("5.0.00")),
    BIND_ART_PATH_POINT_TO_ARTMESH_EXTENSION(ReviewedVersions.since("5.0.00")),
    BIND_ART_PATH_POINT_TO_ARTMESH_EXTENSION_TO_ALL(ReviewedVersions.since("5.0.00")),
    CALIBRATE_ART_PATH_CTRL_POINT(ReviewedVersions.since("5.0.00")),
    CHANGE_ARTMESH_INPUT(ReviewedVersions.since("5.0.00")),
    CHANGE_DEFAULT_KEYFORM_LOCKED(ReviewedVersions.since("5.0.00")),
    CHANGE_SNAP_TO_GUIDE(ReviewedVersions.since("5.0.00")),
    COPY(ReviewedVersions.since("5.0.00")),
    COPY_PARAMETER_BOOKMARK_LABEL(ReviewedVersions.since("5.0.00")),
    COPY_PARAM_SET(ReviewedVersions.since("5.0.00")),
    COPY_SHAPE(ReviewedVersions.since("5.0.00")),
    CREATE_ARTMESH_FOR_HIT_TEST(ReviewedVersions.since("5.0.00")),
    CREATE_ART_PATH_COLLECT(ReviewedVersions.since("5.0.00")),
    CREATE_ART_PATH_INDIVISUALLY(ReviewedVersions.since("5.0.00")),
    CREATE_CONTROLLER_EXTENSION_BY_ART_PATH(ReviewedVersions.since("5.0.00")),
    CREATE_FORMANIMATION_KEY(ReviewedVersions.since("5.0.00")),
    CREATE_FREE_DEFORM_HANDLER_AS_FREE_MODE(ReviewedVersions.since("5.0.00")),
    CREATE_FREE_DEFORM_HANDLER_AS_PERSPECTIVE_MODE(ReviewedVersions.since("5.0.00")),
    CREATE_FREE_DEFORM_HANDLER_AS_ROTATE_MODE(ReviewedVersions.since("5.0.00")),
    CREATE_FREE_DEFORM_HANDLER_AS_SCALE_MODE(ReviewedVersions.since("5.0.00")),
    CREATE_FREE_DEFORM_HANDLER_AS_WARP_MODE(ReviewedVersions.since("5.0.00")),
    CREATE_PATH_HANDLER(ReviewedVersions.since("5.0.00")),
    CREATE_WARP_HANDLER(ReviewedVersions.since("5.0.00")),
    CUT(ReviewedVersions.since("5.0.00")),
    DEFORM_PATH_SKINNING(ReviewedVersions.since("5.0.00")),
    DELETE(ReviewedVersions.since("5.0.00")),
    DELETE_DEFORMER_AND_CHILDREN(ReviewedVersions.since("5.0.00")),
    DELETE_DEFORMER_AND_SET_PARAM(ReviewedVersions.since("5.0.00")),
    DELETE_DEFORMER_ORIGINAL_SHAPE(
            ReviewedVersions.since("5.2.00")),
    DELETE_TEMPORARY_HANDLER(ReviewedVersions.since("5.0.00")),
    DESELECT_INVISIBLE_AND_LOCK_OBJECTS(ReviewedVersions.since("5.0.00")),
    EDIT_LEVEL_1(ReviewedVersions.since("5.0.00")),
    EDIT_LEVEL_2(ReviewedVersions.since("5.0.00")),
    EDIT_LEVEL_3(ReviewedVersions.since("5.0.00")),
    ENABLE_PLAYBACK_CACHE(
            ReviewedVersions.since("5.2.00")),
    ESC(ReviewedVersions.since("5.0.00")),
    ESTIMATE_LINE_WIDTH(ReviewedVersions.since("5.0.00")),
    EXPAND_WARPDEFORMER(ReviewedVersions.line("5.3")),
    FIT_ART_MESH_CONTOUR(ReviewedVersions.since("5.0.00")),
    FOCUS_ON_SELECTED_OBJECTS(ReviewedVersions.since("5.0.00")),
    GENERATE_RAW_IMAGE_FROM_TEXTURE_ATLAS(ReviewedVersions.since("5.0.00")),
    GLUE_BIND(ReviewedVersions.since("5.0.00")),
    GLUE_CHANGE_ENABLE(ReviewedVersions.since("5.0.00")),
    GLUE_SKINNING(ReviewedVersions.since("5.0.00")),
    GLUE_UNBIND(ReviewedVersions.since("5.0.00")),
    HALF_COLUMN_WARP_HANDLER(ReviewedVersions.since("5.0.00")),
    HALF_ROW_WARP_HANDLER(ReviewedVersions.since("5.0.00")),
    HIDE_OBJECT_SELECTION(ReviewedVersions.since("5.0.00")),
    HIDE_OR_RESTORE_PALETTE(ReviewedVersions.since("5.0.03")),
    HIDE_PARAMETER_BOOKMARK_LABEL(ReviewedVersions.since("5.0.00")),
    HIGHLIGHT_VERTICES_OUTSIDE_OF_PARENT_DEFORMER(ReviewedVersions.since("5.0.00")),
    HIGH_LIGHT_DEFORMER_CHILD(ReviewedVersions.since("5.0.00")),
    INSERT_INTER_ART_PATH_POINTS_TO_ALL(ReviewedVersions.since("5.0.00")),
    INSERT_INTER_ART_PATH_POINTS_TO_SELECTED_POINTS(ReviewedVersions.since("5.0.00")),
    LOAD_VISIBLE_MAP(ReviewedVersions.since("5.0.00")),
    MESH_EDIT_CONNECT_AUTO(ReviewedVersions.since("5.0.00")),
    MESH_EDIT_RESET(ReviewedVersions.since("5.3.02")),
    MODELING_MERGE_POINTS(ReviewedVersions.since("5.0.00")),
    MODELING_REFLECTION_AXIS_FOR_MESH_EDIT(ReviewedVersions.since("5.0.00")),
    MODELING_REFLECTION_H(ReviewedVersions.since("5.0.00")),
    MODELING_REFLECTION_V(ReviewedVersions.since("5.0.00")),
    /**
     * @deprecated the {@code CMD_MOVE_End} id appears in no reviewed jar; the empty support
     *     set means the command can never be issued. Kept only for SDK binary compatibility.
     */
    @Deprecated(forRemoval = true)
    MOVE_END(Set.of()),
    /**
     * @deprecated the {@code CMD_MOVE_End_WORKSPACE} id appears in no reviewed jar; the empty
     *     support set means the command can never be issued. Kept only for SDK binary compatibility.
     */
    @Deprecated(forRemoval = true)
    MOVE_END_WORKSPACE(Set.of()),
    MOVE_START(ReviewedVersions.since("5.0.00")),
    MOVE_START_WORKSPACE(ReviewedVersions.since("5.0.00")),
    MOVE_TAB_NEXT(ReviewedVersions.since("5.0.00")),
    MOVE_TAB_PREV(ReviewedVersions.since("5.0.00")),
    MOVE_TRACK_INDICATOR(ReviewedVersions.since("5.0.00")),
    NEW_MODEL(ReviewedVersions.since("5.0.00")),
    NEXT_FRAME(ReviewedVersions.since("5.0.00")),
    NEXT_KEYFRAME(ReviewedVersions.since("5.0.00")),
    NEXT_ONIONSKIN_MARKER(ReviewedVersions.since("5.0.00")),
    NEXT_TIMELINE_MARKER(ReviewedVersions.since("5.0.00")),
    NEXT_TRACK_KEYFRAME(ReviewedVersions.since("5.0.00")),
    NOT_SELECTED_OBJECT_HIDE(ReviewedVersions.since("5.0.00")),
    NOT_SELECTED_OBJECT_LOCK(ReviewedVersions.since("5.0.00")),
    OPEN_ABOUT(ReviewedVersions.since("5.0.00")),
    OPEN_BACKUP_DIR(ReviewedVersions.since("5.0.00")),
    OPEN_COMMUNITY_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_DOWNLOAD_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_FAQ_PAGE(ReviewedVersions.between("5.0.00", "5.2.03")),
    OPEN_HOME_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_LIVE2D_HELP_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_LOG_FILE(ReviewedVersions.since("5.0.00")),
    OPEN_MANUAL_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_PRODUCT_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_SAMPLE_MODEL_PAGE(ReviewedVersions.since("5.0.00")),
    OPEN_STORE_PAGE(ReviewedVersions.since("5.0.00")),
    PARAM_CONTROLLER_CONVERT_TO_LINE_PARAM_CTRL(
            ReviewedVersions.since("5.2.00")),
    PARAM_CONTROLLER_LINE_PARAM_CTRL_REVERSE_DIRECTION(
            ReviewedVersions.since("5.2.00")),
    PARAM_CONTROLLER_PREVIEW_BAKE(
            ReviewedVersions.since("5.2.00")),
    PARAM_CONTROLLER_SPLIT_LINE_PARAM_CTRL(
            ReviewedVersions.since("5.2.00")),
    PART_PALETTE_CMD_CHANGE_VISIBLE_GUIDE_IMAGE(
            ReviewedVersions.since("5.2.00")),
    PASTE(ReviewedVersions.since("5.0.00")),
    PASTE_PARAM_SET(ReviewedVersions.since("5.0.00")),
    PASTE_SHAPE(ReviewedVersions.since("5.0.00")),
    PICK_ART_MESH_ALPHA_FOR_ART_PATH(ReviewedVersions.since("5.0.00")),
    PICK_ART_MESH_COLOR_FOR_ART_PATH(ReviewedVersions.since("5.0.00")),
    PICK_ART_MESH_LINE_INFO(ReviewedVersions.since("5.0.00")),
    PLAY_STOP(ReviewedVersions.since("5.0.00")),
    PREVIEW_MOTIONPATH_ANIMATION(ReviewedVersions.since("5.0.00")),
    PREVIEW_ONIONSKIN_ANIMATOR(ReviewedVersions.since("5.0.00")),
    PREVIEW_ONIONSKIN_FORM_ANIMATION(ReviewedVersions.since("5.0.00")),
    PREVIEW_ONIONSKIN_MODELER(ReviewedVersions.since("5.0.00")),
    PREVIEW_REDUCTION_RATIO_1(ReviewedVersions.since("5.0.00")),
    PREVIEW_REDUCTION_RATIO_2(ReviewedVersions.since("5.0.00")),
    PREVIEW_REDUCTION_RATIO_4(ReviewedVersions.since("5.0.00")),
    PREVIEW_REDUCTION_RATIO_8(ReviewedVersions.since("5.0.00")),
    PREV_FRAME(ReviewedVersions.since("5.0.00")),
    PREV_KEYFRAME(ReviewedVersions.since("5.0.00")),
    PREV_ONIONSKIN_MARKER(ReviewedVersions.since("5.0.00")),
    PREV_TIMELINE_MARKER(ReviewedVersions.since("5.0.00")),
    PREV_TRACK_KEYFRAME(ReviewedVersions.since("5.0.00")),
    REDO(ReviewedVersions.since("5.0.00")),
    RELOAD_MODEL(ReviewedVersions.since("5.0.00")),
    REMOVE_LABELS_ON_SELECTED_TRACKS(ReviewedVersions.since("5.0.00")),
    RESET_CURRENT_WORKSPACE_LAYOUT(ReviewedVersions.since("5.0.00")),
    RESET_MAINCANVAS(ReviewedVersions.since("5.0.00")),
    RESHAPE_WARP_DEFORMER(ReviewedVersions.since("5.0.00")),
    RESTORE_ORIGINAL_SHAPE(ReviewedVersions.since("5.0.00")),
    SAVE(ReviewedVersions.since("5.0.00")),
    SELECTED_OBJECT_UNLOCK(ReviewedVersions.since("5.0.00")),
    SELECTED_OBJECT_VISIBLE(ReviewedVersions.since("5.0.00")),
    SELECT_ALL(ReviewedVersions.since("5.0.00")),
    SELECT_CHILD_ELEMENTS_OF_DEFORMER(ReviewedVersions.since("5.2.03")),
    SELECT_CLIPPED_OBJECT(ReviewedVersions.since("5.0.00")),
    SELECT_CLIPPING_OBJECT(ReviewedVersions.since("5.0.00")),
    SELECT_OBJECT_BY_CURRENT_KEY_VALUE(
            ReviewedVersions.since("5.2.00")),
    SELECT_PARENT_DEFORMER(ReviewedVersions.since("5.0.00")),
    SETTING_GUIDES_MODELING_ADD_CENTER(ReviewedVersions.line("5.3")),
    SETTING_GUIDES_MODELING_ADD_SELECTED_VERTICES(ReviewedVersions.since("5.0.00")),
    SET_ALL_VIEWS_TO_SAME_DISPLAY_POSITION(ReviewedVersions.since("5.0.00")),
    SET_DEFAULT_PARAMETERS(ReviewedVersions.since("5.0.00")),
    SET_END_TRACK(ReviewedVersions.since("5.0.00")),
    SET_END_WORKSPACE(ReviewedVersions.since("5.0.00")),
    SET_SAME_PARAMETERS_TO_ALL_SELECTED(ReviewedVersions.since("5.0.00")),
    SET_START_TRACK(ReviewedVersions.since("5.0.00")),
    SET_START_WORKSPACE(ReviewedVersions.since("5.0.00")),
    SHOW_ARTMESH_POINT(ReviewedVersions.since("5.1.00")),
    SHOW_CHILD_UNDER_WARP_DEFORMER_CTRL_MODE(ReviewedVersions.since("5.0.00")),
    SHOW_DEFAULT_ZOOM(ReviewedVersions.line("5.3")),
    SHOW_DEFORMER_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_FULL_SCENE(ReviewedVersions.line("5.3")),
    SHOW_FULL_WORKSPACE(ReviewedVersions.since("5.0.00")),
    SHOW_GUI_GRID(ReviewedVersions.since("5.0.00")),
    SHOW_GUI_GUIDE(ReviewedVersions.since("5.0.00")),
    SHOW_GUI_GUIDE_FOR_MODELING(ReviewedVersions.since("5.0.00")),
    SHOW_HOME_DIALOG(ReviewedVersions.since("5.1.00")),
    SHOW_INSPECTOR_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_LOG_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_MODEL_BY_RAW_IMAGE(ReviewedVersions.since("5.0.00")),
    SHOW_MODEL_BY_TEXTURE_ATLAS(ReviewedVersions.since("5.0.00")),
    SHOW_PARAMETER_BOOKMARK_FORM_SELECTED_ATTRIBUTE(ReviewedVersions.since("5.0.00")),
    SHOW_PARAMETER_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_PARAM_CONTROLLER_TARGET_TRACKING(
            ReviewedVersions.since("5.2.00")),
    SHOW_PARAM_CTRL_MARKER(
            ReviewedVersions.since("5.2.00")),
    SHOW_PARTS_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_POPUP_HOVERING_OBJECT(ReviewedVersions.since("5.2.03")),
    SHOW_PROJECT_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_SCENE_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_TEMPLATE_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_TIMELINE_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_TOOL_PALETTE(ReviewedVersions.since("5.0.00")),
    SHOW_TUTORIAL_VIDEO(ReviewedVersions.since("5.0.00")),
    SHOW_USER_OPERATION(ReviewedVersions.since("5.0.00")),
    SHOW_VERTEX_INDEX(ReviewedVersions.since("5.0.00")),
    START_OR_END_MESH_EDITOR(ReviewedVersions.since("5.0.00")),
    STORE_CURRENT_WORKSPACE_LAYOUT_AS_DEFAULT(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_ADD_SEGMENT_NUM(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_ADD_WIDTH(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_ON_END_EDIT(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_REDUCE_SEGMENT_NUM(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_REDUCE_WIDTH(ReviewedVersions.since("5.0.00")),
    STROKE_MESH_EDIT_TOGGLE_TYPE(ReviewedVersions.since("5.0.00")),
    SWITCH_EDIT_MODE(ReviewedVersions.since("5.0.00")),
    SWITCH_HIGHTLIGHT_CONFLICT_POLYGON(ReviewedVersions.line("5.3")),
    SWITCH_LOOP_EDITING_SUPPORT(
            ReviewedVersions.since("5.2.00")),
    SYNC_PARAMETERS_ACROSS_TABS(ReviewedVersions.since("5.0.05")),
    TOGGLE_DISPLAY_BOUNDINGBOX(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_ARROW(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_ERASER(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_LASSO(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_POINT_ADD(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_POINT_REMOVE(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_STROKE_MESH(ReviewedVersions.since("5.0.00")),
    TOOL_MODE_MESH_EDIT_SUBDIVIDE_MESH(ReviewedVersions.since("5.0.00")),
    TWICE_COLUMN_WARP_HANDLER(ReviewedVersions.since("5.0.00")),
    TWICE_ROW_WARP_HANDLER(ReviewedVersions.since("5.0.00")),
    UNBIND_ART_PATH_POINT_TO_ARTMESH_EXTENSION(ReviewedVersions.since("5.0.00")),
    UNBIND_ART_PATH_POINT_TO_ARTMESH_EXTENSION_TO_ALL(ReviewedVersions.since("5.0.00")),
    UNDO(ReviewedVersions.since("5.0.00")),
    UPDATE_DEFORMER_ORIGINAL_SHAPE(
            ReviewedVersions.since("5.2.00"));

    private final Set<String> supportedVersions;

    EditorCommand(final Set<String> supportedVersions) {
        this.supportedVersions = Set.copyOf(supportedVersions);
    }

    /**
     * @return the host command identifier: the constant name lowercased and dot-separated
     *     (for example {@code UNDO} becomes {@code undo}), computed with {@code Locale.ROOT} so it
     *     is stable under a Turkish or other locale-sensitive default
     */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
    }

    /**
     * @return the Cubism Editor versions this command was observed on, as an unmodifiable copy
     *     taken at construction; entries include only exact reviewed versions on which the native
     *     command was observed
     */
    public Set<String> supportedVersions() {
        return supportedVersions;
    }

    /**
     * @param cubismVersion an exact Editor version string such as {@code "5.3.02"}; not parsed or
     *     range-matched, only compared for equality
     * @return whether this command is admitted on that version
     */
    public boolean supports(final String cubismVersion) {
        return supportedVersions.contains(cubismVersion);
    }
}
