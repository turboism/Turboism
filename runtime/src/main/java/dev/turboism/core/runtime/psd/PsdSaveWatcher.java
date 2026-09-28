package dev.turboism.core.runtime.psd;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Content-driven save observer for one runtime-allocated PSD.
 *
 * <p>Filesystem events are not trusted: the watcher polls the allocation, hashes it without
 * copying, and lets {@link PsdSaveDebouncer} decide when a version has been stable long enough.
 * Only then does it stage a bounded copy and publish one revision. Failures (missing, changing,
 * unreadable or oversized file) break the stable window and are retried a bounded number of times;
 * they are never reported as a save.</p>
 *
 * <p>All work runs on the single scheduler lane owned by the issuing service, so publications for
 * one plugin are serialized and no two documents interleave inside this watcher. The watcher never
 * deletes a file, never imports anything, and never decides that a native mutation succeeded: it
 * publishes a staged revision and an upper layer owns replacement and its outcome.</p>
 */
final class PsdSaveWatcher implements AutoCloseable {
    static final long DEFAULT_POLL_MILLIS = 250L;
    static final long COMPENSATION_DELAY_MILLIS = 2_000L;
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final PsdTemporaryFile allocation;
    private final Publisher publisher;
    private final PsdSaveDebouncer debouncer;
    private final Scheduler scheduler;
    private final LongSupplier nanoClock;
    private final long pollMillis;
    private final long compensationDelayMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private Cancellation pending;
    private int consecutiveFailures;

    PsdSaveWatcher(
            final PsdTemporaryFile allocation,
            final Publisher publisher,
            final PsdSaveDebouncer debouncer,
            final Scheduler scheduler,
            final LongSupplier nanoClock,
            final long pollMillis,
            final long compensationDelayMillis) {
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.debouncer = Objects.requireNonNull(debouncer, "debouncer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (pollMillis <= 0 || compensationDelayMillis < 0) {
            throw new IllegalArgumentException("invalid PSD watcher cadence");
        }
        this.pollMillis = pollMillis;
        this.compensationDelayMillis = compensationDelayMillis;
    }

    /** Starts polling once; a second call is a no-op. */
    void start() {
        if (stopped.get() || !started.compareAndSet(false, true)) return;
        // A save that lands during the external-application start window must still be found, so an
        // early compensation pass runs before the steady cadence.
        schedule(compensationDelayMillis);
    }

    /** Stops admission and cancels the pending pass; already published revisions stay published. */
    @Override
    public void close() {
        stopped.set(true);
        debouncer.stop();
        final Cancellation current;
        synchronized (this) {
            current = pending;
            pending = null;
        }
        if (current != null) current.cancel();
    }

    private void schedule(final long delayMillis) {
        if (stopped.get()) return;
        final Cancellation cancellation = scheduler.schedule(this::pass, delayMillis);
        synchronized (this) {
            if (stopped.get()) {
                cancellation.cancel();
            } else {
                pending = cancellation;
            }
        }
    }

    private void pass() {
        if (stopped.get()) return;
        try {
            final String digest = PsdStableSnapshot.digestOf(allocation);
            consecutiveFailures = 0;
            final Optional<String> stable = debouncer.observe(digest, nanoClock.getAsLong());
            if (stable.isPresent()) {
                if (publish()) {
                    debouncer.confirm(digest);
                } else {
                    // Abandon the offer so a transient staging/publication failure can still be
                    // retried after a fresh quiet window instead of losing the save.
                    debouncer.invalidateObservation();
                }
            }
        } catch (IOException | RuntimeException observationFailure) {
            // A missing/changing/unreadable file is not a save. Break the window and retry a bounded
            // number of times; a persistently failing allocation keeps polling so a later recovery
            // still publishes the newest stable version.
            debouncer.invalidateObservation();
            consecutiveFailures++;
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0;
            schedule(Math.max(pollMillis, compensationDelayMillis));
        } else {
            schedule(pollMillis);
        }
    }

    /**
     * Stages and publishes one stable version.
     *
     * @return true only when the publication succeeded, so the caller may confirm the digest; a
     *     failed attempt abandons the offer instead of silently consuming the save
     */
    private boolean publish() {
        final PsdStableSnapshot.Snapshot snapshot;
        try {
            snapshot = PsdStableSnapshot.capture(allocation);
        } catch (IOException | RuntimeException stagingFailure) {
            // The file moved again between the stable observation and the copy. A failed capture
            // deliberately keeps the offer unconfirmed, so the same content can still be published
            // once the file settles again.
            return false;
        }
        try {
            publisher.publish(snapshot);
            return true;
        } catch (IOException | RuntimeException publishFailure) {
            // A revoked/stopped handle or an exhausted revision slot fails closed here. The stage
            // stays in the OS temporary directory and the next pass re-offers the content.
            return false;
        }
    }

    /** Publishes one staged stable revision; the handle owns authorization and token issuance. */
    @FunctionalInterface
    interface Publisher {
        void publish(PsdStableSnapshot.Snapshot snapshot) throws IOException;
    }

    /** Minimal scheduling seam so tests can drive passes deterministically. */
    interface Scheduler {
        Cancellation schedule(Runnable task, long delayMillis);
    }

    /** Cancels one scheduled pass; idempotent. */
    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }
}
