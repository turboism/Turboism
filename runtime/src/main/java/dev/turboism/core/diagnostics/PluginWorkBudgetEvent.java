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

    /** The point in a governed task's life an event describes. */
    public enum Phase {
        /** The task was handed to the governor. */
        SUBMITTED,
        /** The task is waiting for an execution slot. */
        QUEUED,
        /** The task began executing. */
        STARTED,
        /** The task exceeded its time budget. */
        TIMED_OUT,
        /** The plugin's circuit breaker opened. */
        CIRCUIT_OPEN,
        /** The governor refused the task. */
        REJECTED,
        /** The task finished normally. */
        COMPLETED,
        /** The task finished with a failure. */
        FAILED
    }

    /** Where the governor placed the work, or that it refused it. */
    public enum Decision {
        /** Runs inline on the bounded plugin executor. */
        LIGHTWEIGHT,
        /** Runs dispatched to the supervised sidecar. */
        SIDECAR,
        /** The task was refused. */
        REJECTED
    }

    /** How prominently an event should be reported. */
    public enum Severity {
        /** Routine record. */
        INFO,
        /** Elevated record worth attention. */
        WARNING,
        /** A failure or refusal. */
        ERROR
    }
}
