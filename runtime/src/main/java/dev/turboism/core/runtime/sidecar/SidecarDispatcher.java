package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.runtime.PluginTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Hands a plugin task to an out-of-process worker.
 *
 * <p>Implementations report in-flight problems as {@link SidecarResult} error or
 * timeout values and reserve exceptional completion for refusing the work
 * outright. {@link #noop()} supplies the fail-closed implementation used where no
 * sidecar exists.</p>
 */
public interface SidecarDispatcher {

    /**
     * Submits a task for out-of-process execution.
     *
     * @param task     the plugin work to run; its fields become the sidecar envelope
     * @param callback run only after the worker completed successfully, never on
     *                 error or timeout
     * @return a stage completing with the outcome; it completes exceptionally with
     *     {@link SidecarDispatchException} only when dispatch was refused outright
     * @throws NullPointerException when {@code task} or {@code callback} is {@code null}
     */
    CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback);

    /**
     * @return whether dispatch is expected to succeed right now; {@code true} by
     *     default. A {@code true} answer is a hint, not a guarantee — the sidecar may
     *     still fail on the next dispatch.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * @return a fail-closed dispatcher that reports {@link #isAvailable()} as
     *     {@code false} and completes every dispatch with a {@code SIDECAR_UNAVAILABLE}
     *     error result, never running the callback and never launching a process
     */
    static SidecarDispatcher noop() {
        return new SidecarDispatcher() {
            @Override
            public CompletionStage<SidecarResult> dispatch(
                final PluginTask task,
                final Runnable callback
            ) {
                Objects.requireNonNull(task, "task");
                Objects.requireNonNull(callback, "callback");
                return CompletableFuture.completedFuture(SidecarResult.error(
                    "SIDECAR_UNAVAILABLE",
                    "Sidecar execution is unavailable."
                ));
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
