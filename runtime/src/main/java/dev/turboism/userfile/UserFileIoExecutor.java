package dev.turboism.userfile;

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

/** Runtime-owned blocking user-file I/O with plugin-executor completion delivery. */
final class UserFileIoExecutor implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final RuntimePluginTaskScheduler tasks;
    private final ThreadPoolExecutor executor;
    private final Object lifecycleLock = new Object();
    private final Set<Operation<?>> operations = new HashSet<>();
    private boolean active = true;

    UserFileIoExecutor(
        final String pluginId,
        final RuntimePluginTaskScheduler tasks
    ) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
                final Thread thread = new Thread(
                    runnable,
                    "turboism-user-file-" + requireText(pluginId, "pluginId")
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    <T> CompletionStage<T> submit(
        final Supplier<T> action,
        final Supplier<T> canceled,
        final Supplier<T> unavailable,
        final Supplier<T> failed
    ) {
        final PluginCompletionFuture<T> completion = future();
        final Operation<T> operation = new Operation<>(
            action,
            canceled,
            failed,
            completion
        );
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
        final PluginCompletionFuture<T> completion = future();
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
                    "User-file I/O did not quiesce before scope close"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for user-file I/O quiescence",
                exception
            );
        }
    }

    private <T> PluginCompletionFuture<T> future() {
        return new PluginCompletionFuture<>(this::dispatch);
    }

    private void dispatch(final Runnable continuation) {
        tasks.dispatchContinuation(continuation);
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
        private final Supplier<T> failed;
        private final PluginCompletionFuture<T> completion;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean settled = new AtomicBoolean(false);

        private Operation(
            final Supplier<T> action,
            final Supplier<T> canceled,
            final Supplier<T> failed,
            final PluginCompletionFuture<T> completion
        ) {
            this.action = Objects.requireNonNull(action, "action");
            this.canceled = Objects.requireNonNull(canceled, "canceled");
            this.failed = Objects.requireNonNull(failed, "failed");
            this.completion = Objects.requireNonNull(completion, "completion");
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true) || settled.get()) {
                remove(this);
                return;
            }
            try {
                settle(Thread.currentThread().isInterrupted()
                    ? canceled.get()
                    : action.get());
            } catch (Throwable failure) {
                settle(failed.get());
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
    }
}
