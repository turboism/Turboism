package dev.turboism.core.runtime.psd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic save-watcher contract: an injected scheduler and monotonic clock drive passes by
 * hand, so no real filesystem events, wall-clock timing or native host behavior are involved.
 */
class PsdSaveWatcherTest {
    @TempDir Path root;

    @Test
    void stableChangePublishesExactlyOnceAndNeverReplays() throws Exception {
        final PsdTemporaryFile allocation = allocation("first");
        final ManualScheduler scheduler = new ManualScheduler();
        final List<String> published = new ArrayList<>();
        try (PsdSaveWatcher watcher = watcher(allocation, scheduler, published)) {
            watcher.start();
            // The compensation pass sees the baseline unchanged: nothing may be published.
            pass(scheduler);
            assertEquals(List.of(), published);

            Files.writeString(allocation.validatedPath(), "second");
            pass(scheduler);
            assertEquals(List.of(), published, "one observation is not yet a stable version");
            pass(scheduler);
            assertEquals(1, published.size(), "a stable change publishes exactly one revision");

            // The same content observed again after the quiet window must not replay.
            pass(scheduler);
            pass(scheduler);
            assertEquals(1, published.size());
        }
    }

    @Test
    void changingFileBreaksTheWindowSoNoUnstableVersionIsPublished() throws Exception {
        final PsdTemporaryFile allocation = allocation("first");
        final ManualScheduler scheduler = new ManualScheduler();
        final List<String> published = new ArrayList<>();
        try (PsdSaveWatcher watcher = watcher(allocation, scheduler, published)) {
            watcher.start();
            pass(scheduler);

            Files.writeString(allocation.validatedPath(), "second");
            pass(scheduler);
            Files.writeString(allocation.validatedPath(), "third");
            pass(scheduler);
            assertEquals(List.of(), published, "a changing file is never a stable save");

            pass(scheduler);
            assertEquals(1, published.size(), "the newest stable content is published afterwards");
        }
    }

    @Test
    void missingFileIsRetriedAndRecoveryStillPublishesTheNewestContent() throws Exception {
        final PsdTemporaryFile allocation = allocation("first");
        final ManualScheduler scheduler = new ManualScheduler();
        final List<String> published = new ArrayList<>();
        try (PsdSaveWatcher watcher = watcher(allocation, scheduler, published)) {
            watcher.start();
            pass(scheduler);

            final Path file = allocation.validatedPath();
            Files.delete(file);
            pass(scheduler);
            assertEquals(List.of(), published, "a missing file is not a save");
            assertTrue(scheduler.hasPending(), "observation continues after a failure");

            Files.writeString(file, "recovered");
            pass(scheduler);
            pass(scheduler);
            assertEquals(1, published.size());
        }
    }

    @Test
    void closeStopsAdmissionAndPublishingButDeletesNothing() throws Exception {
        final PsdTemporaryFile allocation = allocation("first");
        final ManualScheduler scheduler = new ManualScheduler();
        final List<String> published = new ArrayList<>();
        final PsdSaveWatcher watcher = watcher(allocation, scheduler, published);
        watcher.start();
        pass(scheduler);

        Files.writeString(allocation.validatedPath(), "second");
        pass(scheduler);
        watcher.close();
        assertFalse(scheduler.hasPending(), "closing cancels the pending pass");

        scheduler.forceRunAll();
        assertEquals(List.of(), published);
        assertTrue(Files.exists(allocation.validatedPath()), "no file is deleted on close");
    }

    @Test
    void publisherFailureIsIsolatedAndTheSameContentCanStillPublishLater() throws Exception {
        final PsdTemporaryFile allocation = allocation("first");
        final ManualScheduler scheduler = new ManualScheduler();
        final List<String> published = new ArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean(true);
        try (PsdSaveWatcher watcher = new PsdSaveWatcher(
            allocation,
            snapshot -> {
                if (fail.get()) throw new IOException("staging rejected");
                published.add(snapshot.sha256());
            },
            new PsdSaveDebouncer(digestOf("first"), Duration.ofMillis(750)),
            scheduler,
            scheduler::nanoTime,
            PsdSaveWatcher.DEFAULT_POLL_MILLIS,
            PsdSaveWatcher.COMPENSATION_DELAY_MILLIS
        )) {
            watcher.start();
            pass(scheduler);

            Files.writeString(allocation.validatedPath(), "second");
            pass(scheduler);
            pass(scheduler);
            assertEquals(List.of(), published, "a rejected publication publishes nothing");

            fail.set(false);
            pass(scheduler);
            pass(scheduler);
            assertEquals(1, published.size(), "the same content can publish after a retry");
        }
    }

    private PsdSaveWatcher watcher(
        final PsdTemporaryFile allocation,
        final ManualScheduler scheduler,
        final List<String> published
    ) throws IOException {
        return new PsdSaveWatcher(
            allocation,
            snapshot -> published.add(snapshot.sha256()),
            new PsdSaveDebouncer(digestOf("first"), Duration.ofMillis(750)),
            scheduler,
            scheduler::nanoTime,
            PsdSaveWatcher.DEFAULT_POLL_MILLIS,
            PsdSaveWatcher.COMPENSATION_DELAY_MILLIS
        );
    }

    /** Advances the manual clock past the quiet window and runs the single pending pass. */
    private static void pass(final ManualScheduler scheduler) {
        scheduler.advancePastQuietWindow();
        scheduler.runNext();
    }

    private PsdTemporaryFile allocation(final String content) throws IOException {
        return temporary(content);
    }

    private PsdTemporaryFile temporary(final String content) throws IOException {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        Files.writeString(allocation.validatedPath(), content);
        return allocation;
    }

    private String digestOf(final String content) throws IOException {
        return PsdStableSnapshot.digestOf(temporary(content));
    }

    /** Manual lane plus manual monotonic clock; nothing runs unless the test asks it to. */
    private static final class ManualScheduler implements PsdSaveWatcher.Scheduler {
        private static final long QUIET_WINDOW_MILLIS = 750L;

        private final Deque<Runnable> pending = new ArrayDeque<>();
        private long nanos;

        @Override
        public PsdSaveWatcher.Cancellation schedule(final Runnable task, final long delayMillis) {
            pending.addLast(task);
            return () -> pending.remove(task);
        }

        void runNext() {
            final Runnable task = pending.pollFirst();
            if (task == null) throw new AssertionError("no scheduled pass to run");
            task.run();
        }

        void forceRunAll() {
            while (!pending.isEmpty()) {
                pending.pollFirst().run();
            }
        }

        void advancePastQuietWindow() {
            nanos += Duration.ofMillis(QUIET_WINDOW_MILLIS + 1).toNanos();
        }

        long nanoTime() {
            return nanos;
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }
    }
}
