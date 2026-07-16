package dev.turboism.storage;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.task.PluginCompletionFuture;
import dev.turboism.task.RuntimePluginTaskScheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runtime-owned blocking-I/O executor with plugin-executor completion dispatch. */
final class StorageIoExecutor implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final RuntimePluginTaskScheduler taskScheduler;
    private final ThreadPoolExecutor executor;
    private final Object lifecycleLock = new Object();
    private final Set<Operation<?>> operations = new HashSet<>();
    private boolean active = true;

    StorageIoExecutor(
        final String pluginId,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope
    ) {
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
                final Thread thread = new Thread(
                    runnable,
                    "turboism-storage-" + requireText(pluginId, "pluginId")
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        try {
            Objects.requireNonNull(disposableScope, "disposableScope").register(this);
        } catch (RuntimeException exception) {
            executor.shutdownNow();
            throw exception;
        }
    }

    <T> CompletionStage<T> submit(
        final Supplier<T> action,
        final Supplier<T> canceled,
        final Supplier<T> unavailable
    ) {
        final PluginCompletionFuture<T> completion = completionFuture();
        final Operation<T> operation = new Operation<>(action, canceled, completion);
        synchronized (lifecycleLock) {
            if (!active) {
                dispatch(() -> completion.settle(unavailable.get()));
                return completion.stage();
            }
            operations.add(operation);
            try {
                executor.execute(operation);
            } catch (RejectedExecutionException exception) {
                operations.remove(operation);
                dispatch(() -> completion.settle(unavailable.get()));
            }
        }
        return completion.stage();
    }

    <T> CompletionStage<T> immediate(final T value) {
        final PluginCompletionFuture<T> completion = completionFuture();
        dispatch(() -> completion.settle(value));
        return completion.stage();
    }

    @Override
    public void close() {
        final ArrayList<Operation<?>> toCancel;
        synchronized (lifecycleLock) {
            if (!active) {
                return;
            }
            active = false;
            toCancel = new ArrayList<>(operations);
        }
        toCancel.forEach(Operation::cancel);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Plugin storage I/O did not quiesce before scope close"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for plugin storage I/O quiescence",
                exception
            );
        }
    }

    private <T> PluginCompletionFuture<T> completionFuture() {
        return new PluginCompletionFuture<>(this::dispatch);
    }

    private void dispatch(final Runnable continuation) {
        taskScheduler.dispatchContinuation(continuation);
    }

    private void remove(final Operation<?> operation) {
        synchronized (lifecycleLock) {
            operations.remove(operation);
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class Operation<T> implements Runnable {
        private final Supplier<T> action;
        private final Supplier<T> canceled;
        private final PluginCompletionFuture<T> completion;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean settled = new AtomicBoolean(false);

        private Operation(
            final Supplier<T> action,
            final Supplier<T> canceled,
            final PluginCompletionFuture<T> completion
        ) {
            this.action = Objects.requireNonNull(action, "action");
            this.canceled = Objects.requireNonNull(canceled, "canceled");
            this.completion = Objects.requireNonNull(completion, "completion");
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true) || settled.get()) {
                remove(this);
                return;
            }
            try {
                settle(Thread.currentThread().isInterrupted() ? canceled.get() : action.get());
            } catch (Throwable failure) {
                settleExceptionally(failure);
            } finally {
                remove(this);
            }
        }

        private void cancel() {
            if (!started.get()) {
                settle(canceled.get());
                remove(this);
            }
        }

        private void settle(final T value) {
            if (settled.compareAndSet(false, true)) {
                dispatch(() -> completion.settle(value));
            }
        }

        private void settleExceptionally(final Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (settled.compareAndSet(false, true)) {
                dispatch(() -> completion.settleExceptionally(
                    new IllegalStateException("Plugin storage operation failed safely.")
                ));
            }
        }
    }
}
