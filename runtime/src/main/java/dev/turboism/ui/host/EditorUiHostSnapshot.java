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

    public boolean isReady(final EditorUiFamily family) {
        return readyFamilies.contains(Objects.requireNonNull(family, "family"));
    }

    static Set<EditorUiFamily> immutableFamilies(final Set<EditorUiFamily> families) {
        if (families.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(EnumSet.copyOf(families));
    }

    public enum State {
        ABSENT,
        CONNECTING,
        CONNECTED_NOT_READY,
        READY,
        REPLACING,
        FAILED,
        CLOSED
    }
}
