package dev.turboism.automesh;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Answer of {@link AutoMeshDispatch#dispatch} to one native Auto Mesh Generate confirmation.
 *
 * <p>The three cases separate what the host must do next. {@link Unhandled} means the Editor's own
 * generator runs and the host body must stay untouched. {@link Refused} means the custom run never
 * started, so no algorithm ran and no write happened. {@link Started} means a custom run is in
 * flight and its write settles later on the UI thread.</p>
 */
public sealed interface DispatchOutcome
        permits DispatchOutcome.Unhandled, DispatchOutcome.Refused, DispatchOutcome.Started {

    /** The Editor's own generator runs: the dispatcher neither reads nor writes the model. */
    DispatchOutcome UNHANDLED = Unhandled.INSTANCE;

    /** The host runs its own generator body unchanged. */
    enum Unhandled implements DispatchOutcome {
        INSTANCE
    }

    /**
     * The custom run was refused before any read, compute or write.
     *
     * @param reasonCode stable machine token of the refusal
     * @param message user-facing explanation for the native warning area
     */
    record Refused(String reasonCode, String message) implements DispatchOutcome {

        public Refused {
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
            message = Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The custom run started on the compute lane.
     *
     * @param run handle of the started run
     */
    record Started(Run run) implements DispatchOutcome {

        public Started {
            Objects.requireNonNull(run, "run");
        }
    }

    /** Handle of one started custom run. */
    interface Run {

        /**
         * Reports the run's terminal result, delivered on the UI thread.
         *
         * @return a stage that settles exactly once
         */
        CompletionStage<DispatchResult> completion();

        /**
         * Requests cancellation; the write never happens once the request wins.
         *
         * @return {@code true} while the run was still computing and is now cancelled
         */
        boolean cancel();
    }
}
