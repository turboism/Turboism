package dev.turboism.plugin.psdclipmaskimport;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PsdClipMaskImportRunnerTest {

    @Test
    void repeatedRequestFocusesTheExistingProgressAndQueuesOnlyOneImport() {
        final ManualExecutor executor = new ManualExecutor();
        final List<RecordingProgress> progresses = new ArrayList<>();
        final AtomicInteger operations = new AtomicInteger();
        final PsdClipMaskImportRunner runner = new PsdClipMaskImportRunner(
            executor,
            progress -> {
                operations.incrementAndGet();
                progress.awaitingConfirmation();
                progress.applying();
            },
            () -> {
                final RecordingProgress progress = new RecordingProgress();
                progresses.add(progress);
                return progress;
            }
        );

        assertTrue(runner.requestImport());
        assertFalse(runner.requestImport());

        assertEquals(1, executor.queued());
        assertEquals(1, progresses.size());
        assertEquals(1, progresses.get(0).showCount);
        assertEquals(1, progresses.get(0).focusCount);
        assertTrue(runner.isRunning());

        executor.runNext();

        assertEquals(1, operations.get());
        assertEquals(List.of("preparing", "confirming", "applying"), progresses.get(0).stages);
        assertEquals(1, progresses.get(0).closeCount);
        assertFalse(runner.isRunning());
        assertTrue(runner.requestImport(), "completion must admit a later deliberate import");
        runner.close();
    }

    @Test
    void closingRejectsNewImportsAndClosesTheVisibleProgress() {
        final ManualExecutor executor = new ManualExecutor();
        final RecordingProgress progress = new RecordingProgress();
        final PsdClipMaskImportRunner runner = new PsdClipMaskImportRunner(
            executor,
            ignored -> { },
            () -> progress
        );

        assertTrue(runner.requestImport());
        runner.close();

        assertFalse(runner.requestImport());
        assertEquals(1, progress.closeCount);
        assertFalse(runner.isRunning());
        assertTrue(executor.isShutdown());
    }

    @Test
    void closingWaitsForInterruptIgnoringWorkBeforeReturning() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeReturned = new CountDownLatch(1);
        final RecordingProgress progress = new RecordingProgress();
        final PsdClipMaskImportRunner runner = new PsdClipMaskImportRunner(
            ignored -> {
                started.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignoredInterrupt) {
                        // Model writes may ignore interruption once an atomic host call has begun.
                    }
                }
            },
            () -> progress,
            ignored -> { }
        );

        assertTrue(runner.requestImport());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        final Thread closer = new Thread(() -> {
            closeStarted.countDown();
            runner.close();
            closeReturned.countDown();
        });
        closer.start();
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS));

        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        assertTrue(runner.isRunning());
        assertEquals(0, progress.closeCount);
        release.countDown();
        assertTrue(closeReturned.await(2, TimeUnit.SECONDS));
        assertTrue(progress.closed.await(2, TimeUnit.SECONDS));
        assertFalse(runner.isRunning());
    }

    @Test
    void backgroundFailuresAreReportedWithoutEscapingTheExecutorThread() {
        final ManualExecutor executor = new ManualExecutor();
        final AtomicInteger failures = new AtomicInteger();
        final PsdClipMaskImportRunner runner = new PsdClipMaskImportRunner(
            executor,
            ignored -> { throw new IllegalStateException("boom"); },
            RecordingProgress::new,
            ignored -> failures.incrementAndGet()
        );

        assertTrue(runner.requestImport());
        executor.runNext();

        assertEquals(1, failures.get());
        assertFalse(runner.isRunning());
    }

    private static final class RecordingProgress implements PsdClipMaskImportProgress {
        final List<String> stages = new ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        int showCount;
        int focusCount;
        int closeCount;

        @Override public void show() { showCount++; }
        @Override public void preparing() { stages.add("preparing"); }
        @Override public void awaitingConfirmation() { stages.add("confirming"); }
        @Override public void applying() { stages.add("applying"); }
        @Override public void focus() { focusCount++; }
        @Override public boolean cancellationRequested() { return false; }
        @Override public void close() {
            closeCount++;
            closed.countDown();
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private boolean shutdown;

        int queued() { return queued.size(); }

        void runNext() { queued.remove().run(); }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            final List<Runnable> pending = List.copyOf(queued);
            queued.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queued.isEmpty(); }
        @Override public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return isTerminated();
        }
        @Override public void execute(final Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            queued.add(command);
        }
    }
}
