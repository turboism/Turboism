package dev.turboism.tests.runtime.sidecar;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.test.fake.FakeSidecarProcess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Test implementation of {@link SidecarDispatcher} that routes work to a
 * {@link FakeSidecarProcess} instead of launching a real sidecar.
 *
 * <p>The returned future reflects the fake process's configured behavior:
 * <ul>
 *   <li>A queued reply runs the callback and completes the future normally.</li>
 *   <li>A simulated crash completes the future exceptionally without running the callback.</li>
 *   <li>A simulated timeout returns a future that never completes.</li>
 * </ul>
 */
public final class FakeSidecarDispatcher implements SidecarDispatcher {

    private final FakeSidecarProcess process;

    public FakeSidecarDispatcher(FakeSidecarProcess process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    @Override
    public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");

        FakeSidecarProcess.Response response = process.nextResponse();
        if (response == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No sidecar behavior configured for task: " + task.taskType())
            );
        }

        return switch (response.behavior()) {
            case SUCCESS -> {
                callback.run();
                yield CompletableFuture.completedFuture(SidecarResult.success(""));
            }
            case ERROR -> CompletableFuture.failedFuture(
                new RuntimeException("[" + response.errorCode() + "] " + response.errorMessage())
            );
            case TIMEOUT -> new CompletableFuture<>();
        };
    }
}
