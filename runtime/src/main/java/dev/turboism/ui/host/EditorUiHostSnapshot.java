package dev.turboism.ui.host;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable Editor UI host state exposed to runtime policy modules. */
public record EditorUiHostSnapshot(
    State state,
    long generation,
    Set<EditorUiFamily> readyFamilies,
    Optional<EditorUiHostFailure> failure
) {
    public EditorUiHostSnapshot {
        state = Objects.requireNonNull(state, "state");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        Objects.requireNonNull(readyFamilies, "readyFamilies");
        readyFamilies = Set.copyOf(readyFamilies);
        failure = Objects.requireNonNull(failure, "failure");
        if ((state == State.ABSENT || state == State.CONNECTING || state == State.CLOSED)
            && !readyFamilies.isEmpty()) {
            throw new IllegalArgumentException(state + " must not expose ready UI families");
        }
        if (state == State.READY && readyFamilies.isEmpty()) {
            throw new IllegalArgumentException("READY requires at least one ready UI family");
        }
    }

    /**
     * @return the snapshot used when no Editor UI host is present: state {@code ABSENT},
     *     generation 0, no ready families, and a {@code HOST_UNAVAILABLE} failure
     */
    public static EditorUiHostSnapshot safeMode() {
        return new EditorUiHostSnapshot(
            State.ABSENT,
            0,
            Set.of(),
            Optional.of(EditorUiHostFailure.host(
                EditorUiHostFailure.Code.HOST_UNAVAILABLE,
                "Editor UI host is unavailable."
            ))
        );
    }

    /**
     * @param family UI family to test
     * @return true when this snapshot lists the family as ready; always false in states that are
     *     forbidden from exposing ready families ({@code ABSENT}, {@code CONNECTING}, {@code CLOSED})
     * @throws NullPointerException if {@code family} is null
     */
    public boolean isReady(final EditorUiFamily family) {
        return readyFamilies.contains(Objects.requireNonNull(family, "family"));
    }

    static Set<EditorUiFamily> immutableFamilies(final Set<EditorUiFamily> families) {
        if (families.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(EnumSet.copyOf(families));
    }

    /** Lifecycle state of the Editor UI host integration. */
    public enum State {
        /** No host integration exists. */
        ABSENT,
        /** A host connection is being established. */
        CONNECTING,
        /** Connected, but not all families are ready. */
        CONNECTED_NOT_READY,
        /** All admitted families are installed and live. */
        READY,
        /** The live host registration is being replaced. */
        REPLACING,
        /** The host left its healthy path; see the failure record. */
        FAILED,
        /** The integration has been closed. */
        CLOSED
    }
}
