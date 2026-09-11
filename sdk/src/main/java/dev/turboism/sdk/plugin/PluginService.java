package dev.turboism.sdk.plugin;

/**
 * One optional service slot on {@link PluginContext}.
 *
 * <p>Members name the getter they describe, so
 * {@code context.availableServices().contains(PluginService.STORAGE)} tells the plugin that
 * {@code context.storage()} resolves to an installed service. Guaranteed core members of
 * {@link PluginContext} ({@code descriptor}, {@code logger}, {@code paths}, {@code cubism},
 * {@code permissions}, {@code eventBus}, {@code actions}, {@code menus}, {@code uiScheduler},
 * {@code diagnostics}, {@code disposableScope}) have no member because they cannot be absent.</p>
 *
 * <p>Presence means the getter returns a usable service object; it does not imply the plugin holds
 * the permissions that service's operations require, and it does not guarantee individual
 * version-routed members succeed on the active host.</p>
 */
public enum PluginService {

    /** {@link PluginContext#localization()} */
    LOCALIZATION,

    /** {@link PluginContext#tasks()} */
    TASKS,

    /** {@link PluginContext#hostReads()} */
    HOST_READS,

    /** {@link PluginContext#storage()} */
    STORAGE,

    /** {@link PluginContext#scripts()} */
    SCRIPTS,

    /** {@link PluginContext#userFiles()} */
    USER_FILES,

    /** {@link PluginContext#parameterQuery()} */
    PARAMETER_QUERY,

    /** {@link PluginContext#selectionQuery()} */
    SELECTION_QUERY,

    /** {@link PluginContext#modelHierarchyQuery()} */
    MODEL_HIERARCHY_QUERY,

    /** {@link PluginContext#cubismRead()} */
    CUBISM_READ,

    /** {@link PluginContext#modelObjects()} */
    MODEL_OBJECTS,

    /** {@link PluginContext#cubismClipMasks()} */
    CUBISM_CLIP_MASKS,

    /** {@link PluginContext#recentFiles()} */
    RECENT_FILES,

    /** {@link PluginContext#screenshots()} */
    SCREENSHOTS,

    /** {@link PluginContext#recentPreviews()} */
    RECENT_PREVIEWS,

    /** {@link PluginContext#physicsEditor()} */
    PHYSICS_EDITOR,

    /** {@link PluginContext#fileChooserHistory()} */
    FILE_CHOOSER_HISTORY,

    /** {@link PluginContext#meshMirrorAxis()} */
    MESH_MIRROR_AXIS,

    /** {@link PluginContext#meshEdit()} */
    MESH_EDIT,

    /** {@link PluginContext#meshEditParticipation()} */
    MESH_EDIT_PARTICIPATION,

    /** {@link PluginContext#meshMirrorCounterparts()} */
    MESH_MIRROR_COUNTERPARTS,

    /** {@link PluginContext#meshMirrorToolEligibility()} */
    MESH_MIRROR_TOOL_ELIGIBILITY,

    /** {@link PluginContext#meshMirrorMoveParticipation()} */
    MESH_MIRROR_MOVE_PARTICIPATION,

    /** {@link PluginContext#meshEditUi()} */
    MESH_EDIT_UI,

    /** {@link PluginContext#editorCommands()} */
    EDITOR_COMMANDS,

    /** {@link PluginContext#backup()} */
    BACKUP,

    /** {@link PluginContext#mainToolbar()} */
    MAIN_TOOLBAR,

    /** {@link PluginContext#paletteToolbar()} */
    PALETTE_TOOLBAR,

    /** {@link PluginContext#paletteFilter()} */
    PALETTE_FILTER,

    /** {@link PluginContext#sceneTable()} */
    SCENE_TABLE,

    /** {@link PluginContext#uiHost()} */
    UI_HOST,

    /** {@link PluginContext#hostDialogs()} */
    HOST_DIALOGS,

    /** {@link PluginContext#appearance()} */
    APPEARANCE,

    /** {@link PluginContext#workspace()} */
    WORKSPACE,

    /** {@link PluginContext#workspaceLayout()} */
    WORKSPACE_LAYOUT,

    /** {@link PluginContext#contextMenu()} */
    CONTEXT_MENU,

    /** {@link PluginContext#config()} */
    CONFIG,

    /** {@link PluginContext#cubismLog()} */
    CUBISM_LOG,

    /** {@link PluginContext#runtimeSettings()} */
    RUNTIME_SETTINGS,

    /** {@link PluginContext#mcpConnections()} */
    MCP_CONNECTIONS,

    /** {@link PluginContext#performanceStats()} */
    PERFORMANCE_STATS
}
