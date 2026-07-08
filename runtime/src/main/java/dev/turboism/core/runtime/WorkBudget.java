package dev.turboism.core.runtime;

/**
 * Classification of how much host-side execution budget a plugin task may consume.
 */
public enum WorkBudget {
    LIGHTWEIGHT,
    SIDECAR,
    REJECTED
}
