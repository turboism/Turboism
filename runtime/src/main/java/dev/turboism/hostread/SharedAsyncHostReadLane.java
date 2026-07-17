package dev.turboism.hostread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Host-session-owned serialized lane shared by all plugin-scoped host-read services. */
public final class SharedAsyncHostReadLane implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<String> workerThreadName = new AtomicReference<>("");
    private final Object lifecycleLock = new Object();
    private final Set<RuntimeAsyncHostReadHandle> remaining = new HashSet<>();

    public SharedAsyncHostReadLane(final int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                final Thread thread = new Thread(() -> {
                    workerThreadName.set(Thread.currentThread().getName());
                    runnable.run();
                }, "turboism-host-read-shared");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            final Thread thread = new Thread(runnable, "turboism-host-read-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.setRemoveOnCancelPolicy(true);
    }

    Admission admit(
        final RuntimeAsyncHostReadHandle handle,
        final Duration timeout,
        final Runnable action
    ) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(action, "action");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return Admission.RUNTIME_UNAVAILABLE;
            }
            final PhysicalOperation operation = new PhysicalOperation(handle, action);
            final ScheduledFuture<?> deadline;
            try {
                deadline = timer.schedule(
                    handle::timeout,
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS
                );
            } catch (RejectedExecutionException exception) {
                return Admission.RUNTIME_UNAVAILABLE;
            }
            handle.attach(operation::cancel, () -> deadline.cancel(false));
            handle.physicalAdmitted();
            remaining.add(handle);
            try {
                executor.execute(operation);
                return Admission.ACCEPTED;
            } catch (RejectedExecutionException exception) {
                deadline.cancel(false);
                remaining.remove(handle);
                operation.rejectBeforeQueue();
                return closed.get() ? Admission.RUNTIME_UNAVAILABLE : Admission.BACKPRESSURE;
            }
        }
    }

    void physicalOperationFinished(final RuntimeAsyncHostReadHandle handle) {
        synchronized (lifecycleLock) {
            remaining.remove(handle);
        }
    }

    public String workerThreadName() {
        return workerThreadName.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        final ArrayList<RuntimeAsyncHostReadHandle> toCancel;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            toCancel = new ArrayList<>(remaining);
        }
        toCancel.forEach(RuntimeAsyncHostReadHandle::cancelFromSharedLane);
        executor.shutdownNow();
        timer.shutdownNow();
        final long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        await(executor, deadline, "Shared async host-read lane did not quiesce before close");
        await(timer, deadline, "Shared async host-read deadline timer did not quiesce before close");
    }

    private static void await(
        final java.util.concurrent.ExecutorService service,
        final long deadline,
        final String message
    ) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new IllegalStateException(message);
        }
        try {
            if (!service.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    enum Admission {
        ACCEPTED,
        BACKPRESSURE,
        RUNTIME_UNAVAILABLE
    }

    private final class PhysicalOperation implements Runnable {
        private final RuntimeAsyncHostReadHandle handle;
        private final Runnable action;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean exited = new AtomicBoolean(false);
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile Thread runner;

        private PhysicalOperation(
            final RuntimeAsyncHostReadHandle handle,
            final Runnable action
        ) {
            this.handle = handle;
            this.action = action;
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            runner = Thread.currentThread();
            try {
                if (!canceled.get() && handle.beginRunning()) {
                    action.run();
                }
            } finally {
                runner = null;
                exitOnce();
            }
        }

        private void cancel() {
            canceled.set(true);
            if (!started.get() && executor.remove(this)) {
                exitOnce();
                return;
            }
            final Thread running = runner;
            if (running != null) {
                running.interrupt();
            }
        }

        private void rejectBeforeQueue() {
            canceled.set(true);
            exitOnce();
        }

        private void exitOnce() {
            if (exited.compareAndSet(false, true)) {
                physicalOperationFinished(handle);
                handle.physicalExited();
            }
        }
    }

    interface OperationCancellation {
        void cancel();
    }
}
