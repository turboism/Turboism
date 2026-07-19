package dev.turboism.plugin.projectpanel.b1.domain;

public record ProjectPanelStateModel(
    Active active,
    ProjectPhase lastPhase,
    int openingCount,
    int openedCount,
    int closingCount,
    int closedCount,
    long revision
) {
    private static final int MAX_COUNTER = 1_000_000;

    public ProjectPanelStateModel {
        active = active == null ? Active.INACTIVE : active;
        requireCounter(openingCount);
        requireCounter(openedCount);
        requireCounter(closingCount);
        requireCounter(closedCount);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    public static ProjectPanelStateModel defaults() {
        return hydrate(null, 0, 0, 0, 0);
    }

    public static ProjectPanelStateModel hydrate(
        final ProjectPhase lastPhase,
        final int openingCount,
        final int openedCount,
        final int closingCount,
        final int closedCount
    ) {
        return new ProjectPanelStateModel(
            Active.INACTIVE, lastPhase, openingCount, openedCount, closingCount, closedCount, 0
        );
    }

    public ProjectPanelReduction activate() {
        if (active == Active.ACTIVE) {
            return reduction(ProjectPhaseResult.DUPLICATE);
        }
        return new ProjectPanelReduction(new ProjectPanelStateModel(
            Active.ACTIVE, lastPhase, openingCount, openedCount, closingCount, closedCount, revision + 1
        ), ProjectPhaseResult.APPLIED);
    }

    public ProjectPanelReduction deactivate() {
        if (active == Active.INACTIVE) {
            return reduction(ProjectPhaseResult.DUPLICATE);
        }
        return new ProjectPanelReduction(new ProjectPanelStateModel(
            Active.INACTIVE, lastPhase, openingCount, openedCount, closingCount, closedCount, revision + 1
        ), ProjectPhaseResult.APPLIED);
    }

    public ProjectPanelReduction apply(final ProjectPhase next) {
        if (active == Active.INACTIVE) {
            return reduction(ProjectPhaseResult.INACTIVE);
        }
        if (next == null || next == lastPhase) {
            return reduction(ProjectPhaseResult.DUPLICATE);
        }
        if (!allowed(lastPhase, next)) {
            return reduction(ProjectPhaseResult.INVALID_TRANSITION);
        }
        final int current = counter(next);
        if (current >= MAX_COUNTER) {
            return reduction(ProjectPhaseResult.COUNTER_LIMIT);
        }
        return new ProjectPanelReduction(new ProjectPanelStateModel(
            active,
            next,
            openingCount + (next == ProjectPhase.OPENING ? 1 : 0),
            openedCount + (next == ProjectPhase.OPENED ? 1 : 0),
            closingCount + (next == ProjectPhase.CLOSING ? 1 : 0),
            closedCount + (next == ProjectPhase.CLOSED ? 1 : 0),
            revision + 1
        ), ProjectPhaseResult.APPLIED);
    }

    private int counter(final ProjectPhase phase) {
        return switch (phase) {
            case OPENING -> openingCount;
            case OPENED -> openedCount;
            case CLOSING -> closingCount;
            case CLOSED -> closedCount;
        };
    }

    private static boolean allowed(final ProjectPhase previous, final ProjectPhase next) {
        if (previous == null) {
            return next == ProjectPhase.OPENING || next == ProjectPhase.OPENED;
        }
        return switch (previous) {
            case OPENING -> next == ProjectPhase.OPENED || next == ProjectPhase.CLOSING || next == ProjectPhase.CLOSED;
            case OPENED -> next == ProjectPhase.CLOSING || next == ProjectPhase.CLOSED;
            case CLOSING -> next == ProjectPhase.CLOSED || next == ProjectPhase.OPENING;
            case CLOSED -> next == ProjectPhase.OPENING || next == ProjectPhase.OPENED;
        };
    }

    private ProjectPanelReduction reduction(final ProjectPhaseResult value) {
        return new ProjectPanelReduction(this, value);
    }

    private static void requireCounter(final int value) {
        if (value < 0 || value > MAX_COUNTER) {
            throw new IllegalArgumentException("counter is outside supported bounds");
        }
    }

    public enum Active {
        INACTIVE,
        ACTIVE
    }
}
