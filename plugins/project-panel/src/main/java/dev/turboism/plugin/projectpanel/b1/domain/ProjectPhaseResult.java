package dev.turboism.plugin.projectpanel.b1.domain;

/**
 * Why {@link ProjectPanelStateModel} accepted or refused a transition.
 *
 * <p>Every value except {@code APPLIED} means the state was left exactly as it was.
 */
public enum ProjectPhaseResult {
    /** The transition was accepted; the reduction carries a new state with an advanced revision. */
    APPLIED,
    /** Refused because the panel is not active; phase events are ignored while deactivated. */
    INACTIVE,
    /**
     * Refused as a no-op: the requested phase was {@code null} or already the current phase, or the
     * requested activation state was already in effect.
     */
    DUPLICATE,
    /** Refused because the requested phase cannot follow the previous one. */
    INVALID_TRANSITION,
    /** Refused because the counter for the requested phase has reached its supported maximum. */
    COUNTER_LIMIT
}
