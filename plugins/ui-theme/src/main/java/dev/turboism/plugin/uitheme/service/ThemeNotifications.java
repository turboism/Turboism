package dev.turboism.plugin.uitheme.service;

import dev.turboism.sdk.plugin.PluginLogger;
import java.util.Objects;

/**
 * Operator-visible reporting shared by the theme workflows.
 *
 * <p>A failing asynchronous workflow has no caller to throw at, so it reports through
 * {@link #workflowFailed}: the log then names both the workflow and the cause, and every status
 * notification picks its severity here instead of repeating the literal.</p>
 */
public final class ThemeNotifications {

    /** Severity of a status notification that reports a completed change. */
    public static final String INFO = "INFO";

    /** Severity of a status notification that reports a refused or failed operation. */
    public static final String WARNING = "WARNING";

    /** Diagnostic code prefix every workflow failure is logged under. */
    public static final String WORKFLOW_FAILURE_CODE = "UI_THEME_WORKFLOW_FAILED ";

    public static final String WORKFLOW_OPEN_MANAGER = "manager-open";
    public static final String WORKFLOW_RELOAD = "reload";
    public static final String WORKFLOW_MANAGER_ACTION = "manager-action";
    public static final String WORKFLOW_APPLY = "apply";
    public static final String WORKFLOW_APPLY_BUILTIN = "apply-builtin";
    public static final String WORKFLOW_RESTORE_NATIVE = "restore-native";
    public static final String WORKFLOW_RESTORE_SELECTION = "restore-selection";
    public static final String WORKFLOW_IMPORT = "import";
    public static final String WORKFLOW_EXPORT = "export";
    public static final String WORKFLOW_DELETE = "delete";
    public static final String WORKFLOW_EDIT = "edit-theme";
    public static final String WORKFLOW_SAVE_THEME = "save-theme";
    public static final String WORKFLOW_SAVE_REFRESH = "save-refresh";

    private ThemeNotifications() {}

    /**
     * Records one workflow failure.
     *
     * @param logger the plugin logger the failure is reported to
     * @param workflow the workflow constant naming what failed
     * @param failure the cause; {@code null} means the workflow succeeded and nothing is logged
     */
    public static void workflowFailed(final PluginLogger logger, final String workflow, final Throwable failure) {
        if (failure != null) {
            Objects.requireNonNull(logger, "logger")
                    .warn(WORKFLOW_FAILURE_CODE + Objects.requireNonNull(workflow, "workflow") + " " + failure);
        }
    }
}
