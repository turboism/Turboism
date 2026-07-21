package dev.turboism.core.diagnostics;

import java.util.Objects;

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
