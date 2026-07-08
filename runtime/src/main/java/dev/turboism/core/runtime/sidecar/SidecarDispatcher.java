package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.runtime.PluginTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface SidecarDispatcher {

    CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback);

    static SidecarDispatcher noop() {
        return (task, callback) -> {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(callback, "callback");
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        };
    }
}
