package dev.turboism.core.runtime;

/**
 * Decides the {@link WorkBudget} for a {@link PluginTask}.
 */
public interface WorkBudgetPolicy {
    WorkBudget classify(PluginTask task);
}
