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
    public static final String TURBOISM_UI_MENU_CONTRIBUTE = "turboism.ui.menu.contribute";
    public static final String TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE = "turboism.ui.toolbar.main.contribute";
    public static final String TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE = "turboism.ui.toolbar.palette.contribute";
    public static final String TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE = "turboism.ui.context-menu.contribute";
    public static final String TURBOISM_UI_CONTEXT_SOURCE_READ = "turboism.ui.context-source.read";
    public static final String TURBOISM_UI_OVERLAY_CONTRIBUTE = "turboism.ui.overlay.contribute";
    public static final String TURBOISM_UI_VIEWPORT_READ = "turboism.ui.viewport.read";
    public static final String TURBOISM_UI_DIALOG_CONTRIBUTE = "turboism.ui.dialog.contribute";
    public static final String TURBOISM_UI_DIALOG_AUTOMATE = "turboism.ui.dialog.automate";
    public static final String TURBOISM_UI_PANEL_CONTRIBUTE = "turboism.ui.panel.contribute";
    public static final String TURBOISM_UI_FILE_CHOOSER_REQUEST = "turboism.ui.file-chooser.request";
    public static final String TURBOISM_UI_STATUS_NOTIFY = "turboism.ui.status.notify";
    public static final String TURBOISM_UI_APPEARANCE_MODIFY = "turboism.ui.appearance.modify";
    public static final String TURBOISM_UI_APPEARANCE_OBSERVE = "turboism.ui.appearance.observe";
    public static final String TURBOISM_UI_TOOLBAR_CONTRIBUTE = "turboism.ui.toolbar.contribute";
    public static final String TURBOISM_CONFIG_PLUGIN_READ = "turboism.config.plugin.read";
    public static final String TURBOISM_CONFIG_PLUGIN_WRITE = "turboism.config.plugin.write";
    public static final String TURBOISM_CUBISM_MODEL_READ = "turboism.cubism.model.read";
    public static final String TURBOISM_CUBISM_MODEL_WRITE = "turboism.cubism.model.write";

    public static final String TURBOISM_CUBISM_MODEL_OBSERVE =
        "turboism.cubism.model.observe";
    public static final String TURBOISM_CUBISM_MODEL_INTERCEPT =
        "turboism.cubism.model.intercept";
    public static final String TURBOISM_CUBISM_BACKUP_OBSERVE =
        "turboism.cubism.backup.observe";
    public static final String TURBOISM_CUBISM_RECENT_FILE_READ = "turboism.cubism.recent-file.read";
    public static final String TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE = "turboism.ui.recent-preview.contribute";
    public static final String TURBOISM_EVENT_PUBLISH = "turboism.event.publish";
    public static final String TURBOISM_EVENT_SUBSCRIBE = "turboism.event.subscribe";
    public static final String TURBOISM_FILE_READ = "turboism.file.read";
    public static final String TURBOISM_FILE_WRITE = "turboism.file.write";
    public static final String TURBOISM_PERFORMANCE_STATS_READ =
        "turboism.performance.stats.read";
    public static final String TURBOISM_HOST_UNSAFE = "turboism.host.unsafe";
    public static final String TURBOISM_NETWORK = "turboism.network.fetch";
    public static final String TURBOISM_PROCESS = "turboism.process.run";

    private PermissionIds() {}
}
