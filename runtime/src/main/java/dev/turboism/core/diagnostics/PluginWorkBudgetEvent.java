package dev.turboism.core.diagnostics;

import java.util.Objects;

/**
 * Diagnostic record of one decision the plugin work-budget governor made about
 * a background task.
 *
 * @param pluginId id of the plugin that owns the task
 * @param taskId   id of the governed task
 * @param phase    the point in the task’s life this event describes
 * @param decision where the governor placed the work, or that it refused it
 * @param severity how prominently the event should be reported
 * @throws NullPointerException when any component is {@code null}
 */
public record PluginWorkBudgetEvent(
    String pluginId,
    String taskId,
    Phase phase,
    Decision decision,
    Severity severity
) {

    public static final String CODE = "PLUGIN_WORK_BUDGET_EVENT";

    public PluginWorkBudgetEvent {
        pluginId = Objects.requireNonNull(pluginId, "pluginId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        phase = Objects.requireNonNull(phase, "phase");
        decision = Objects.requireNonNull(decision, "decision");
        severity = Objects.requireNonNull(severity, "severity");
    }

    /**
     * @return the stable diagnostic code {@value #CODE}, identical for every
     *     instance, used to route and filter this event kind
     */
    public String code() {
        return CODE;
    }

    public enum Phase {
        SUBMITTED,
        QUEUED,
        STARTED,
        TIMED_OUT,
        CIRCUIT_OPEN,
        REJECTED,
        COMPLETED,
        FAILED
    }

    public enum Decision {
        LIGHTWEIGHT,
        SIDECAR,
        REJECTED
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
