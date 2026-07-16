package dev.turboism.task;

import dev.turboism.sdk.task.TaskFailure;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

abstract class AbstractRuntimeTaskHandle implements TaskHandle {

    private final TaskId id;
    private final Runnable terminalCleanup;
    private final Consumer<Runnable> completionDispatcher;
    private final PluginCompletionFuture<TaskOutcome> completion;
    private final AtomicReference<TaskOutcome> terminalOutcome = new AtomicReference<>();

    AbstractRuntimeTaskHandle(
        final TaskId id,
        final Runnable terminalCleanup,
        final Consumer<Runnable> completionDispatcher
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.terminalCleanup = Objects.requireNonNull(terminalCleanup, "terminalCleanup");
        this.completionDispatcher = Objects.requireNonNull(
            completionDispatcher,
            "completionDispatcher"
        );
        this.completion = new PluginCompletionFuture<>(completionDispatcher);
    }

    @Override
    public final TaskId id() {
        return id;
    }

    @Override
    public final CompletionStage<TaskOutcome> completion() {
        return completion.stage();
    }

    final boolean complete(final TaskOutcome outcome) {
        final TaskOutcome terminal = Objects.requireNonNull(outcome, "outcome");
        if (!terminalOutcome.compareAndSet(null, terminal)) {
            return false;
        }
        terminalCleanup.run();
        onTerminal();
        completionDispatcher.accept(() -> completion.settle(terminal));
        return true;
    }

    final boolean isTerminal() {
        return terminalOutcome.get() != null;
    }

    void onTerminal() {
    }

    static TaskFailure failure(final String code, final String message) {
        return new TaskFailure(code, message);
    }
}
