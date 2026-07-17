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
    private final Consumer<TaskOutcome> terminalObserver;
    private final Consumer<Runnable> settlementDispatcher;
    private final PluginCompletionFuture<TaskOutcome> completion;
    private final AtomicReference<TaskOutcome> terminalOutcome = new AtomicReference<>();

    AbstractRuntimeTaskHandle(
        final TaskId id,
        final Runnable terminalCleanup,
        final Consumer<TaskOutcome> terminalObserver,
        final Consumer<Runnable> settlementDispatcher,
        final Consumer<Runnable> continuationDispatcher
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.terminalCleanup = Objects.requireNonNull(terminalCleanup, "terminalCleanup");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
        this.settlementDispatcher = Objects.requireNonNull(
            settlementDispatcher,
            "settlementDispatcher"
        );
        this.completion = new PluginCompletionFuture<>(
            Objects.requireNonNull(continuationDispatcher, "continuationDispatcher")
        );
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
        terminalObserver.accept(terminal);
        terminalCleanup.run();
        onTerminal();
        settlementDispatcher.accept(() -> completion.settle(terminal));
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
