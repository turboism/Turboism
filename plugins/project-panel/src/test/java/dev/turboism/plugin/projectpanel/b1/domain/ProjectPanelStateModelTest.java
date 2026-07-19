package dev.turboism.plugin.projectpanel.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ProjectPanelStateModelTest {

    @Test
    void reducesActivationTransitionsDuplicatesAndInvalidInput() {
        ProjectPanelStateModel model = ProjectPanelStateModel.defaults();
        assertEquals(ProjectPhaseResult.INACTIVE, model.apply(ProjectPhase.OPENING).result());
        final ProjectPanelReduction activated = model.activate();
        model = activated.state();
        assertEquals(1, model.revision());
        assertEquals(ProjectPanelStateModel.Active.ACTIVE, model.active());

        ProjectPanelReduction result = model.apply(ProjectPhase.OPENING);
        assertEquals(ProjectPhaseResult.APPLIED, result.result());
        model = result.state();
        assertEquals(1, model.openingCount());
        assertEquals(2, model.revision());
        assertEquals(ProjectPhaseResult.DUPLICATE, model.apply(ProjectPhase.OPENING).result());
        assertEquals(ProjectPhaseResult.INVALID_TRANSITION, model.apply(ProjectPhase.CLOSED).state()
            .apply(ProjectPhase.CLOSING).result());

        model = model.apply(ProjectPhase.OPENED).state().apply(ProjectPhase.CLOSING).state().apply(ProjectPhase.OPENING).state();
        assertEquals(ProjectPhase.OPENING, model.lastPhase());
        assertEquals(2, model.openingCount());
    }

    @Test
    void activationNoopsAndHydrationDoesNotIncrementRevision() {
        ProjectPanelStateModel model = ProjectPanelStateModel.hydrate(
            ProjectPhase.CLOSED, 1, 2, 3, 4
        );
        assertEquals(0, model.revision());
        model = model.activate().state();
        assertEquals(1, model.revision());
        assertEquals(ProjectPhaseResult.DUPLICATE, model.activate().result());
        model = model.deactivate().state();
        assertEquals(2, model.revision());
        assertEquals(ProjectPhaseResult.DUPLICATE, model.deactivate().result());
        assertEquals(4, model.closedCount());
    }

    @Test
    void counterLimitRejectsTheWholeTransition() {
        ProjectPanelStateModel model = ProjectPanelStateModel.hydrate(
            ProjectPhase.CLOSED, 1_000_000, 0, 0, 0
        ).activate().state();
        final ProjectPanelReduction limited = model.apply(ProjectPhase.OPENING);
        assertEquals(ProjectPhaseResult.COUNTER_LIMIT, limited.result());
        assertEquals(model, limited.state());
    }
}
