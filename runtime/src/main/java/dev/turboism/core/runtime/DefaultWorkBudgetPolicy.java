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
                 "lifecycle.disable",
                 "lifecycle.shutdown",
                 "event.subscribe",
                 "action.handle",
                 "ui.schedule",
                 "sidecar.complete" -> WorkBudget.LIGHTWEIGHT;
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
}
