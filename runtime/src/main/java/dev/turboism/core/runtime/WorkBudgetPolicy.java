package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.WorkBudget;

/**
 * Decides the {@link WorkBudget} for a {@link PluginTask}.
 */
public interface WorkBudgetPolicy {
    /**
     * @param task the submitted plugin task
     * @return the budget the task is admitted under
     */
    WorkBudget classify(PluginTask task);
}
