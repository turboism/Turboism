package dev.turboism.plugin.projectpanel.b1.domain;

import java.util.Objects;

public record ProjectPanelReduction(ProjectPanelStateModel state, ProjectPhaseResult result) {
    public ProjectPanelReduction {
        state = Objects.requireNonNull(state, "state");
        result = Objects.requireNonNull(result, "result");
    }
}
