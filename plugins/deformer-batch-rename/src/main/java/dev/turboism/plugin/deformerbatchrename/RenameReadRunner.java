package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.cubism.model.ModelNamingIndex;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admits at most one naming read and keeps the action handler outside the long-running work.
 *
 * <p>The read runs on a worker thread because the Editor validates every collection it reports; on
 * a large model that takes long enough to freeze the Editor if it runs on the event dispatch
 * thread. The result is handed back through {@code onReady}, which the caller dispatches to the UI
 * thread.</p>
 */
final class RenameReadRunner implements AutoCloseable {

    /** Reads the active model's naming index. */
    @FunctionalInterface
    interface ReadOperation {
        ModelNamingIndex read();
    }

    /** Receives one read result, or the failure that ended the read. */
    interface ResultSink {
        void ready(ModelNamingIndex index);

        void failed(Throwable failure);
    }

    private final ExecutorService executor;
    private final ReadOperation operation;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<RenameReadProgress> activeProgress = new AtomicReference<>();

    RenameReadRunner(final ReadOperation operation) {
        this(defaultExecutor(), operation);
    }

    RenameReadRunner(final ExecutorService executor, final ReadOperation operation) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /**
     * Starts one read, or brings the active progress window to the front on a repeated request.
     *
     * @param progress the progress window to show for this read
     * @param sink the result receiver; called on the worker thread
     * @return {@code true} when the read was started
     */
    boolean requestRead(final RenameReadProgress progress, final ResultSink sink) {
        if (closed.get()) return false;
        if (!running.compareAndSet(false, true)) {
            final RenameReadProgress active = activeProgress.get();
            if (active != null) active.focus();
            return false;
        }
        activeProgress.set(progress);
        progress.show();
        progress.reading();
        try {
            executor.execute(() -> runRead(progress, sink));
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            if (!(failure instanceof RejectedExecutionException)) sink.failed(failure);
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
        executor.shutdownNow();
        final RenameReadProgress progress = activeProgress.getAndSet(null);
        if (progress != null) progress.close();
    }

    private void runRead(final RenameReadProgress progress, final ResultSink sink) {
        try {
            final ModelNamingIndex index = operation.read();
            if (index == null) throw new IllegalStateException("The naming read returned no index.");
            progress.preparing();
            sink.ready(index);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            sink.failed(failure);
        } finally {
            finish(progress);
        }
    }

    private void finish(final RenameReadProgress progress) {
        activeProgress.compareAndSet(progress, null);
        progress.close();
        running.set(false);
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "turboism-deformer-rename-read");
            thread.setDaemon(true);
            return thread;
        });
    }
}
