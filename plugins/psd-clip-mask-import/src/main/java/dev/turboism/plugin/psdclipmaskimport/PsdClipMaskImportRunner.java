package dev.turboism.plugin.psdclipmaskimport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Admits at most one import and keeps the action handler outside the long-running work. */
final class PsdClipMaskImportRunner implements AutoCloseable {

    @FunctionalInterface
    interface ImportOperation {
        void run(PsdClipMaskImportProgress progress);
    }

    private final ExecutorService executor;
    private final ImportOperation operation;
    private final Supplier<PsdClipMaskImportProgress> progressFactory;
    private final Consumer<Throwable> failureHandler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<PsdClipMaskImportProgress> activeProgress =
        new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private volatile Thread runningThread;

    PsdClipMaskImportRunner(
        final ImportOperation operation,
        final Supplier<PsdClipMaskImportProgress> progressFactory,
        final Consumer<Throwable> failureHandler
    ) {
        this(defaultExecutor(), operation, progressFactory, failureHandler);
    }

    PsdClipMaskImportRunner(
        final ExecutorService executor,
        final ImportOperation operation,
        final Supplier<PsdClipMaskImportProgress> progressFactory
    ) {
        this(executor, operation, progressFactory, ignored -> { });
    }

    PsdClipMaskImportRunner(
        final ExecutorService executor,
        final ImportOperation operation,
        final Supplier<PsdClipMaskImportProgress> progressFactory,
        final Consumer<Throwable> failureHandler
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.progressFactory = Objects.requireNonNull(progressFactory, "progressFactory");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    boolean requestImport() {
        if (closed.get()) return false;
        if (!running.compareAndSet(false, true)) {
            final PsdClipMaskImportProgress progress = activeProgress.get();
            if (progress != null) progress.focus();
            return false;
        }

        final PsdClipMaskImportProgress progress = createProgress();
        activeProgress.set(progress);
        progress.preparing();
        progress.show();
        try {
            executor.execute(() -> runImport(progress));
            return true;
        } catch (Throwable failure) {
            if (!(failure instanceof RejectedExecutionException)) reportFailure(failure);
            finish(progress);
            return false;
        }
    }

    boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        final List<Runnable> pending = executor.shutdownNow();
        if (!pending.isEmpty()) {
            final PsdClipMaskImportProgress progress = activeProgress.get();
            if (progress != null) finish(progress);
        }
        awaitFinished();
    }

    private void runImport(final PsdClipMaskImportProgress progress) {
        runningThread = Thread.currentThread();
        try {
            if (!progress.cancellationRequested()) operation.run(progress);
        } catch (Throwable failure) {
            reportFailure(failure);
        } finally {
            finish(progress);
        }
    }

    private void finish(final PsdClipMaskImportProgress progress) {
        activeProgress.compareAndSet(progress, null);
        progress.close();
        synchronized (lifecycleLock) {
            runningThread = null;
            running.set(false);
            lifecycleLock.notifyAll();
        }
    }

    private void awaitFinished() {
        if (Thread.currentThread() == runningThread) return;
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            while (running.get()) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private PsdClipMaskImportProgress createProgress() {
        final PsdClipMaskImportProgress progress;
        try {
            progress = progressFactory.get();
        } catch (Throwable unavailable) {
            reportFailure(unavailable);
            return guarded(PsdClipMaskImportProgress.noop());
        }
        return guarded(progress == null ? PsdClipMaskImportProgress.noop() : progress);
    }

    private PsdClipMaskImportProgress guarded(final PsdClipMaskImportProgress delegate) {
        return new PsdClipMaskImportProgress() {
            @Override public void show() { safely(delegate::show); }
            @Override public void preparing() { safely(delegate::preparing); }
            @Override public void awaitingConfirmation() { safely(delegate::awaitingConfirmation); }
            @Override public void applying() { safely(delegate::applying); }
            @Override public void focus() { safely(delegate::focus); }
            @Override public boolean cancellationRequested() {
                if (closed.get() || Thread.currentThread().isInterrupted()) return true;
                try {
                    return delegate.cancellationRequested();
                } catch (Throwable failure) {
                    reportFailure(failure);
                    return true;
                }
            }
            @Override public void close() { safely(delegate::close); }
        };
    }

    private void safely(final Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            reportFailure(failure);
        }
    }

    private void reportFailure(final Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable ignored) {
            // A failing diagnostic sink must not escape onto the executor thread.
        }
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "turboism-psd-clip-mask-import");
            thread.setDaemon(true);
            return thread;
        });
    }
}
