package dev.turboism.plugin.projectpanel.b1.domain;

/**
 * Immutable state of the project panel, plus the pure transition rules that advance it.
 *
 * <p>All transitions return a {@link ProjectPanelReduction} rather than mutating: on success a new
 * state with {@code revision} incremented, on refusal this same instance with a reason. Nothing here
 * touches the Cubism host or any UI, so it is safe to evaluate off the host thread.
 *
 * <p>Construction normalises a {@code null} {@code active} to {@link Active#INACTIVE} and rejects a
 * negative {@code revision} or any counter outside 0..1,000,000 with {@link IllegalArgumentException}.
 *
 * @param active whether the panel is currently observing project events
 * @param lastPhase the most recent phase applied, {@code null} before any phase has been seen
 * @param openingCount how many times {@link ProjectPhase#OPENING} has been applied
 * @param openedCount how many times {@link ProjectPhase#OPENED} has been applied
 * @param closingCount how many times {@link ProjectPhase#CLOSING} has been applied
 * @param closedCount how many times {@link ProjectPhase#CLOSED} has been applied
 * @param revision monotonically increasing count of accepted transitions; never negative, and
 *                 unchanged by a refused transition
 */
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

    /**
     * @return the starting state: inactive, no phase seen, all counters and the revision at zero
     */
    public static ProjectPanelStateModel defaults() {
        return hydrate(null, 0, 0, 0, 0);
    }

    /**
     * Rebuilds a state from persisted counters, for example when the panel is restored across a
     * session.
     *
     * <p>The result is always {@link Active#INACTIVE} with {@code revision} reset to zero: activation
     * and revision are runtime facts and are deliberately not restored.
     *
     * @param lastPhase the phase last seen before persisting, or {@code null} if none was
     * @param openingCount restored OPENING count
     * @param openedCount restored OPENED count
     * @param closingCount restored CLOSING count
     * @param closedCount restored CLOSED count
     * @return the rehydrated state
     * @throws IllegalArgumentException if any counter is negative or exceeds 1,000,000
     */
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

    /**
     * Marks the panel as observing project events.
     *
     * @return {@link ProjectPhaseResult#APPLIED} with an activated state and an advanced revision, or
     *         {@link ProjectPhaseResult#DUPLICATE} with this state unchanged if already active
     */
    public ProjectPanelReduction activate() {
        if (active == Active.ACTIVE) {
            return reduction(ProjectPhaseResult.DUPLICATE);
        }
        return new ProjectPanelReduction(new ProjectPanelStateModel(
            Active.ACTIVE, lastPhase, openingCount, openedCount, closingCount, closedCount, revision + 1
        ), ProjectPhaseResult.APPLIED);
    }

    /**
     * Stops the panel observing project events. Counters and {@code lastPhase} are retained, so a
     * later {@link #activate()} resumes from the same history.
     *
     * @return {@link ProjectPhaseResult#APPLIED} with a deactivated state and an advanced revision, or
     *         {@link ProjectPhaseResult#DUPLICATE} with this state unchanged if already inactive
     */
    public ProjectPanelReduction deactivate() {
        if (active == Active.INACTIVE) {
            return reduction(ProjectPhaseResult.DUPLICATE);
        }
        return new ProjectPanelReduction(new ProjectPanelStateModel(
            Active.INACTIVE, lastPhase, openingCount, openedCount, closingCount, closedCount, revision + 1
        ), ProjectPhaseResult.APPLIED);
    }

    /**
     * Applies a project phase transition, incrementing that phase's counter when accepted.
     *
     * <p>Refusals, checked in this order and each leaving the state untouched:
     * {@link ProjectPhaseResult#INACTIVE} when the panel is not active;
     * {@link ProjectPhaseResult#DUPLICATE} when {@code next} is {@code null} or equals the current
     * phase; {@link ProjectPhaseResult#INVALID_TRANSITION} when the phase cannot follow the previous
     * one (from no phase, only OPENING or OPENED are reachable); and
     * {@link ProjectPhaseResult#COUNTER_LIMIT} once that phase's counter reaches 1,000,000.
     *
     * @param next the phase to move to; {@code null} is treated as a duplicate, not an error
     * @return the resulting state paired with the verdict; never {@code null}
     */
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

    /** Whether the panel is currently observing project lifecycle events. */
    public enum Active {
        INACTIVE,
        ACTIVE
    }
}
