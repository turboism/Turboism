package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.WorkBudget;

/**
 * Decides the {@link WorkBudget} for a {@link PluginTask}.
 */
public interface WorkBudgetPolicy {
    WorkBudget classify(PluginTask task);
}
