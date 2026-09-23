package dev.turboism.sdk.script;


import java.util.concurrent.CompletionStage;

/** Handle for a running or queued script execution. */
public interface ScriptRunHandle extends AutoCloseable {

    /** Returns the run's stable execution identifier. */
    ScriptExecutionId id();

    /** Returns the stage that completes with the run's final result. */
    CompletionStage<ScriptRunResult> completion();

    /** Requests cancellation. Returns false when execution was already terminal. */
    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
