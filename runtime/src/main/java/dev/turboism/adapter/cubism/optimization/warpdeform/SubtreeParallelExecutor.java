package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntConsumer;

/**
 * Bounded daemon worker pool for level-order subtree deformation and ArtMesh transform batches.
 *
 * <p>Executes disjoint range chunks across worker threads while keeping the caller thread in the
 * work partition, and joins every chunk strictly between DAG depth levels.</p>
 */
final class SubtreeParallelExecutor implements AutoCloseable {

    static final int MIN_PARALLEL_DEFORMERS = 16;
    static final int MIN_PARALLEL_MESHES = 32;
    private static final int MAX_WORKERS = 8;
    private static final int SINGLE_WORKER = 1;
    private static final int QUEUE_MULTIPLIER = 4;
    private static final String WORKER_THREAD_PREFIX = "turboism-deform-worker-";

    private final int workerCount;
    private final ThreadPoolExecutor pool;

    /** Creates a bounded daemon pool resolving context classes through the runtime loader fallback. */
    SubtreeParallelExecutor() {
        this(null);
    }

    /** Creates a bounded daemon pool sized to host processor count up to eight workers. */
    SubtreeParallelExecutor(final ClassLoader contextLoader) {
        final int cpus = Math.max(SINGLE_WORKER, Runtime.getRuntime().availableProcessors());
        this.workerCount = Math.min(cpus, MAX_WORKERS);
        if (this.workerCount <= SINGLE_WORKER) {
            this.pool = null;
        } else {
            // Agent classes run on the bootstrap loader (null defining loader); resolve host/system fallback.
            final ClassLoader effectiveLoader = contextLoader != null
                    ? contextLoader
                    : RuntimeThreads.contextClassLoader(SubtreeParallelExecutor.class);
            final ThreadFactory factory = RuntimeThreads.daemonThreads(WORKER_THREAD_PREFIX, effectiveLoader);
            this.pool = RuntimeThreads.fixedLane(workerCount, workerCount * QUEUE_MULTIPLIER, factory);
        }
    }

    /** Returns true when more than one hardware thread is available for parallel chunks. */
    boolean isParallelCapable() {
        return pool != null && !pool.isShutdown();
    }

    /**
     * Evaluates {@code [0, count)} sequentially or in bounded chunks across the daemon worker pool.
     *
     * @param count number of independent items in the batch
     * @param minParallelThreshold minimum count required before waking worker threads
     * @param task side-effect-free element action receiving index {@code [0, count)}
     */
    void executeBatch(final int count, final int minParallelThreshold, final IntConsumer task) {
        if (count <= 0) {
            return;
        }
        if (pool == null || pool.isShutdown() || count < minParallelThreshold) {
            runSlice(0, count, task);
            return;
        }
        final int totalChunks = Math.min(workerCount, count);
        if (totalChunks <= SINGLE_WORKER) {
            runSlice(0, count, task);
            return;
        }
        final int baseChunk = count / totalChunks;
        final int remainder = count % totalChunks;
        @SuppressWarnings("unchecked")
        final Future<?>[] pending = new Future<?>[totalChunks - SINGLE_WORKER];
        int offset = baseChunk + (remainder > 0 ? 1 : 0);
        final int localEnd = offset;
        int submitted = 0;
        try {
            for (int c = 1; c < totalChunks; c++) {
                final int start = offset;
                final int len = baseChunk + (c < remainder ? 1 : 0);
                final int end = start + len;
                offset = end;
                pending[submitted++] = pool.submit(() -> runSlice(start, end, task));
            }
            // Execute chunk zero on the caller thread so synchronization waits only for peers.
            runSlice(0, localEnd, task);
            for (int i = 0; i < submitted; i++) {
                pending[i].get();
            }
        } catch (InterruptedException interrupted) {
            cancelRemaining(pending, submitted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("subtree deformation interrupted", interrupted);
        } catch (ExecutionException failed) {
            cancelRemaining(pending, submitted);
            final Throwable cause = failed.getCause();
            FatalErrors.rethrowIfFatal(cause);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("subtree deformation worker failed", cause);
        } catch (RuntimeException | Error failed) {
            cancelRemaining(pending, submitted);
            FatalErrors.rethrowIfFatal(failed);
            throw failed;
        }
    }

    private static void runSlice(final int start, final int end, final IntConsumer task) {
        for (int i = start; i < end; i++) {
            task.accept(i);
        }
    }

    private static void cancelRemaining(final Future<?>[] pending, final int submitted) {
        for (int i = 0; i < submitted; i++) {
            final Future<?> future = pending[i];
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
