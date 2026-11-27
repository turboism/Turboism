package dev.turboism.adapter.cubism.textureatlas.cache;

import dev.turboism.runtime.exec.RuntimeThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bounded parallel {@link AtlasPageScheduler} that executes independent page rebuilds on daemon
 * workers and cancels remaining tasks on the first failure.
 */
final class ParallelAtlasPageScheduler implements AtlasPageScheduler {

    private static final int MAX_WORKERS = 8;
    private static final int MIN_WORKERS = 1;
    private static final int SINGLE_PAGE = 1;
    private static final String THREAD_PREFIX = RuntimeThreads.THREAD_NAME_PREFIX + "-atlas-page";

    private static final ExecutorService DEFAULT_POOL = createWorkerPool(defaultWorkerCount());

    private final ExecutorService executor;

    ParallelAtlasPageScheduler() {
        this.executor = DEFAULT_POOL;
    }

    ParallelAtlasPageScheduler(final ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void runPages(final List<? extends Runnable> pageRebuilds) {
        if (pageRebuilds == null || pageRebuilds.isEmpty()) {
            return;
        }
        if (pageRebuilds.size() == SINGLE_PAGE) {
            pageRebuilds.get(0).run();
            return;
        }

        final List<Future<?>> futures = new ArrayList<>(pageRebuilds.size());
        for (final Runnable task : pageRebuilds) {
            futures.add(executor.submit(task));
        }

        Throwable firstFailure = null;
        for (final Future<?> future : futures) {
            try {
                future.get();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelRemaining(futures);
                if (firstFailure == null) {
                    firstFailure = new IllegalStateException("Atlas page rebuild interrupted", interrupted);
                }
                break;
            } catch (final ExecutionException failed) {
                if (firstFailure == null) {
                    firstFailure = failed.getCause() != null ? failed.getCause() : failed;
                    cancelRemaining(futures);
                }
            }
        }
        if (firstFailure != null) {
            rethrow(firstFailure);
        }
    }

    private static void cancelRemaining(final List<Future<?>> futures) {
        for (final Future<?> future : futures) {
            future.cancel(false);
        }
    }

    private static void rethrow(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Atlas page rebuild failed", failure);
    }

    private static int defaultWorkerCount() {
        return Math.min(Math.max(MIN_WORKERS, Runtime.getRuntime().availableProcessors()), MAX_WORKERS);
    }

    private static ExecutorService createWorkerPool(final int workers) {
        return RuntimeThreads.fixedLane(
                workers,
                new LinkedBlockingQueue<>(),
                RuntimeThreads.daemonThreads(
                        THREAD_PREFIX, RuntimeThreads.contextClassLoader(ParallelAtlasPageScheduler.class)));
    }
}
