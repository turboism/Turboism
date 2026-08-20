package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.concurrent.CompletionStage;

/** Handle for a running or queued script execution. */
@PreviewApi
public interface ScriptRunHandle extends AutoCloseable {

    ScriptExecutionId id();

    CompletionStage<ScriptRunResult> completion();

    /** Requests cancellation. Returns false when execution was already terminal. */
    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
