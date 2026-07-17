package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.runtime.PluginTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface SidecarDispatcher {

    CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback);

    default boolean isAvailable() {
        return true;
    }

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
