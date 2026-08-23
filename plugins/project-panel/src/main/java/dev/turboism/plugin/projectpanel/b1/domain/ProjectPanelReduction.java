package dev.turboism.plugin.projectpanel.b1.domain;

import java.util.Objects;

/**
 * The outcome of one attempted transition on {@link ProjectPanelStateModel}.
 *
 * <p>Pairs the state to use going forward with the verdict on the attempt. When {@code result} is
 * anything other than {@link ProjectPhaseResult#APPLIED}, {@code state} is the unchanged prior state -
 * a rejected transition never mutates or advances anything.
 *
 * @param state the state to carry forward; never {@code null}
 * @param result why the transition was applied or refused; never {@code null}
 */
public record ProjectPanelReduction(ProjectPanelStateModel state, ProjectPhaseResult result) {
    public ProjectPanelReduction {
        state = Objects.requireNonNull(state, "state");
        result = Objects.requireNonNull(result, "result");
    }
}
