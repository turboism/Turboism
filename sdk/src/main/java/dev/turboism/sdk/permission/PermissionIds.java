package dev.turboism.sdk.permission;

/**
 * The canonical string identifiers for every permission the runtime recognises.
 *
 * <p>Plugins declare these ids in their manifests and the runtime matches grants against
 * them by exact string equality, so referencing these constants rather than literals keeps
 * declaration and enforcement in step. Not instantiable.
 */
public final class PermissionIds {

    public static final String TURBOISM_ACTION_REGISTER = "turboism.action.register";
    public static final String TURBOISM_ACTION_INVOKE = "turboism.action.invoke";
    public static final String TURBOISM_UI_MENU = "turboism.ui.menu";
    public static final String TURBOISM_UI_TOOLBAR = "turboism.ui.toolbar";
    public static final String TURBOISM_UI_PALETTE = "turboism.ui.palette";
    public static final String TURBOISM_UI_MENU_CONTRIBUTE = "turboism.ui.menu.contribute";
    public static final String TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE = "turboism.ui.toolbar.main.contribute";
    public static final String TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE = "turboism.ui.toolbar.palette.contribute";
    public static final String TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE = "turboism.ui.toolbar.mesh.contribute";
    public static final String TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE = "turboism.ui.context-menu.contribute";
    public static final String TURBOISM_UI_CONTEXT_SOURCE_READ = "turboism.ui.context-source.read";
    public static final String TURBOISM_UI_OVERLAY_CONTRIBUTE = "turboism.ui.overlay.contribute";
    public static final String TURBOISM_UI_VIEWPORT_READ = "turboism.ui.viewport.read";
    public static final String TURBOISM_UI_DIALOG_CONTRIBUTE = "turboism.ui.dialog.contribute";
    public static final String TURBOISM_UI_DIALOG_AUTOMATE = "turboism.ui.dialog.automate";
    public static final String TURBOISM_UI_PANEL_CONTRIBUTE = "turboism.ui.panel.contribute";
    public static final String TURBOISM_UI_SETTINGS_CONTRIBUTE = "turboism.ui.settings.contribute";
    public static final String TURBOISM_UI_FILE_CHOOSER_REQUEST = "turboism.ui.file-chooser.request";
    public static final String TURBOISM_UI_STATUS_NOTIFY = "turboism.ui.status.notify";
    public static final String TURBOISM_UI_CANVAS_HINT = "turboism.ui.canvas.hint";
    public static final String TURBOISM_UI_APPEARANCE_MODIFY = "turboism.ui.appearance.modify";
    public static final String TURBOISM_UI_APPEARANCE_OBSERVE = "turboism.ui.appearance.observe";
    public static final String TURBOISM_UI_TOOLBAR_CONTRIBUTE = "turboism.ui.toolbar.contribute";
    public static final String TURBOISM_CONFIG_PLUGIN_READ = "turboism.config.plugin.read";
    public static final String TURBOISM_CONFIG_PLUGIN_WRITE = "turboism.config.plugin.write";
    public static final String TURBOISM_CUBISM_PROJECT_READ = "turboism.cubism.project.read";
    public static final String TURBOISM_CUBISM_MODEL_READ = "turboism.cubism.model.read";
    public static final String TURBOISM_CUBISM_MODEL_WRITE = "turboism.cubism.model.write";
    public static final String TURBOISM_CUBISM_EDIT = "turboism.cubism.edit";
    public static final String TURBOISM_CUBISM_PARAMETER_READ = "turboism.cubism.parameter.read";
    public static final String TURBOISM_CUBISM_MESH_READ = "turboism.cubism.mesh.read";

    public static final String TURBOISM_CUBISM_MODEL_OBSERVE = "turboism.cubism.model.observe";
    public static final String TURBOISM_CUBISM_MODEL_INTERCEPT = "turboism.cubism.model.intercept";
    public static final String TURBOISM_CUBISM_BACKUP_OBSERVE = "turboism.cubism.backup.observe";
    public static final String TURBOISM_CUBISM_SELECTION_OBSERVE = "turboism.cubism.selection.observe";
    public static final String TURBOISM_UI_SCENE_TABLE_OBSERVE = "turboism.ui.scene-table.observe";
    public static final String TURBOISM_CUBISM_LOG_OBSERVE = "turboism.cubism.log.observe";
    public static final String TURBOISM_PERFORMANCE_SAMPLE_OBSERVE = "turboism.performance.sample.observe";
    public static final String TURBOISM_ACTION_INVOCATION_OBSERVE = "turboism.action.invocation.observe";
    public static final String TURBOISM_PLUGIN_LIFECYCLE_OBSERVE = "turboism.plugin.lifecycle.observe";
    public static final String TURBOISM_CUBISM_RECENT_FILE_READ = "turboism.cubism.recent-file.read";
    public static final String TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE = "turboism.ui.recent-preview.contribute";
    public static final String TURBOISM_EVENT_PUBLISH = "turboism.event.publish";
    public static final String TURBOISM_EVENT_SUBSCRIBE = "turboism.event.subscribe";
    public static final String TURBOISM_FILE_READ = "turboism.file.read";
    public static final String TURBOISM_FILE_WRITE = "turboism.file.write";
    public static final String TURBOISM_PERFORMANCE_STATS_READ = "turboism.performance.stats.read";
    public static final String TURBOISM_HOST_UNSAFE = "turboism.host.unsafe";
    public static final String TURBOISM_NETWORK = "turboism.network.fetch";
    public static final String TURBOISM_PROCESS = "turboism.process.run";
    public static final String TURBOISM_MCP_CONNECTION_READ = "turboism.mcp.connection.read";
    public static final String TURBOISM_MCP_CONNECTION_PUBLISH = "turboism.mcp.connection.publish";

    /**
     * Every permission id the runtime recognises, referencing the constants
     * above so there is exactly one enumeration to extend. {@code Set.of}
     * rejects a duplicated value at class initialization, so a repeated entry
     * fails closed instead of drifting.
     */
    public static final java.util.Set<String> KNOWN_IDS = java.util.Set.of(
            TURBOISM_ACTION_REGISTER,
            TURBOISM_ACTION_INVOKE,
            TURBOISM_UI_MENU,
            TURBOISM_UI_TOOLBAR,
            TURBOISM_UI_PALETTE,
            TURBOISM_UI_MENU_CONTRIBUTE,
            TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE,
            TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE,
            TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE,
            TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE,
            TURBOISM_UI_CONTEXT_SOURCE_READ,
            TURBOISM_UI_OVERLAY_CONTRIBUTE,
            TURBOISM_UI_VIEWPORT_READ,
            TURBOISM_UI_DIALOG_CONTRIBUTE,
            TURBOISM_UI_DIALOG_AUTOMATE,
            TURBOISM_UI_PANEL_CONTRIBUTE,
            TURBOISM_UI_SETTINGS_CONTRIBUTE,
            TURBOISM_UI_FILE_CHOOSER_REQUEST,
            TURBOISM_UI_STATUS_NOTIFY,
            TURBOISM_UI_CANVAS_HINT,
            TURBOISM_UI_APPEARANCE_MODIFY,
            TURBOISM_UI_APPEARANCE_OBSERVE,
            TURBOISM_UI_TOOLBAR_CONTRIBUTE,
            TURBOISM_CONFIG_PLUGIN_READ,
            TURBOISM_CONFIG_PLUGIN_WRITE,
            TURBOISM_CUBISM_PROJECT_READ,
            TURBOISM_CUBISM_MODEL_READ,
            TURBOISM_CUBISM_MODEL_WRITE,
            TURBOISM_CUBISM_EDIT,
            TURBOISM_CUBISM_PARAMETER_READ,
            TURBOISM_CUBISM_MESH_READ,
            TURBOISM_CUBISM_MODEL_OBSERVE,
            TURBOISM_CUBISM_MODEL_INTERCEPT,
            TURBOISM_CUBISM_BACKUP_OBSERVE,
            TURBOISM_CUBISM_SELECTION_OBSERVE,
            TURBOISM_UI_SCENE_TABLE_OBSERVE,
            TURBOISM_CUBISM_LOG_OBSERVE,
            TURBOISM_PERFORMANCE_SAMPLE_OBSERVE,
            TURBOISM_ACTION_INVOCATION_OBSERVE,
            TURBOISM_PLUGIN_LIFECYCLE_OBSERVE,
            TURBOISM_CUBISM_RECENT_FILE_READ,
            TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE,
            TURBOISM_EVENT_PUBLISH,
            TURBOISM_EVENT_SUBSCRIBE,
            TURBOISM_FILE_READ,
            TURBOISM_FILE_WRITE,
            TURBOISM_PERFORMANCE_STATS_READ,
            TURBOISM_HOST_UNSAFE,
            TURBOISM_NETWORK,
            TURBOISM_PROCESS,
            TURBOISM_MCP_CONNECTION_READ,
            TURBOISM_MCP_CONNECTION_PUBLISH);

    private PermissionIds() {}
}
