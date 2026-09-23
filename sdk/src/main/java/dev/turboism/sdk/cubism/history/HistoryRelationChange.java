package dev.turboism.sdk.cubism.history;

import java.util.Objects;
import java.util.Optional;

/** Immutable direct hierarchy relation change captured for one history operation. */
public record HistoryRelationChange(
    Kind kind,
    Endpoint before,
    Endpoint after
) {

    public HistoryRelationChange {
        kind = Objects.requireNonNull(kind, "kind");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
    }

    /** The direct relation family that changed. */
    public enum Kind {
        PART_MEMBERSHIP,
        DEFORMER_PARENT
    }

    /** The captured state of one direct relation endpoint. */
    public enum State {
        TARGET,
        ROOT,
        UNKNOWN
    }

    /** Immutable endpoint value; target endpoints require a stable target ID. */
    public record Endpoint(
        State state,
        Optional<HistoryTarget> target
    ) {

        public Endpoint {
            state = Objects.requireNonNull(state, "state");
            target = Objects.requireNonNull(target, "target");
            switch (state) {
                case TARGET -> {
                    final HistoryTarget value = target.orElseThrow(() ->
                        new IllegalArgumentException("TARGET endpoint requires a target"));
                    if (value.id().isEmpty()) {
                        throw new IllegalArgumentException("TARGET endpoint requires a target ID");
                    }
                }
                case ROOT, UNKNOWN -> {
                    if (target.isPresent()) {
                        throw new IllegalArgumentException(
                            state + " endpoint must not contain a target"
                        );
                    }
                }
            }
        }
    }
}
