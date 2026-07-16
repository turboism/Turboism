package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.WorkBudget;
import java.util.Objects;

/**
 * Default runtime policy that classifies tasks by their operation type and declared capability.
 */
public final class DefaultWorkBudgetPolicy implements WorkBudgetPolicy {

    private static final String SIDECAR_CAPABILITY = "sidecar";

    @Override
    public WorkBudget classify(PluginTask task) {
        Objects.requireNonNull(task, "task must not be null");
        String type = task.taskType();
        if (type == null || type.isBlank()) {
            return WorkBudget.REJECTED;
        }

        return switch (type) {
            case "lifecycle.init",
                 "lifecycle.enable",
                 "lifecycle.disable",
                 "lifecycle.shutdown",
                 "event.subscribe",
                 "ui.schedule",
                 "plugin.compute.normal",
                 "plugin.compute.low",
                 "plugin.refresh.normal",
                 "plugin.refresh.low",
                 "sidecar.complete" -> WorkBudget.LIGHTWEIGHT;
            case "action.handle" -> isHeavyAction(task) ? WorkBudget.HEAVY : WorkBudget.LIGHTWEIGHT;
            case "config.read",
                 "config.write",
                 "transaction.commit",
                 "transaction.rollback" -> WorkBudget.HEAVY;
            case "network",
                 "ai",
                 "file-scan",
                 "heavy-analysis" -> hasSidecarCapability(task)
                ? WorkBudget.SIDECAR
                : WorkBudget.REJECTED;
            default -> WorkBudget.REJECTED;
        };
    }

    private static boolean hasSidecarCapability(PluginTask task) {
        return SIDECAR_CAPABILITY.equals(task.declaredCapability());
    }

    /**
     * Parameter CSV import/export may parse large bounded payloads on the action path.
     * Keep ordinary actions lightweight, but classify those known heavy actions as HEAVY.
     */
    private static boolean isHeavyAction(PluginTask task) {
        final String payload = task.payloadDescription();
        if (payload == null || payload.isBlank()) {
            return false;
        }
        return payload.contains("parameter.csv.import")
            || payload.contains("parameter.csv.export");
    }
}
