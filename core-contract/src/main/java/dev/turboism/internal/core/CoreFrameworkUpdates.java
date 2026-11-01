package dev.turboism.internal.core;

import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Internal UI boundary for installing framework updates. No filesystem or network work on the UI thread. */
public interface CoreFrameworkUpdates extends AutoCloseable {
    /**
     * User-visible update lifecycle. {@code STAGED} means verified bytes are ready;
     * {@code APPLY_PENDING} means application is scheduled after the current Cubism process exits.
     * {@code UNAVAILABLE} means this installation cannot use framework self-update.
     */
    enum Phase {
        UNAVAILABLE,
        IDLE,
        DOWNLOADING,
        STAGED,
        APPLY_PENDING,
        CANCELLED,
        FAILED
    }

    /**
     * Immutable progress for the framework update window.
     *
     * @param phase current update phase
     * @param version target version, or an empty string before a target is known
     * @param verifiedBytes bytes verified against the authenticated target, never greater than the total
     * @param totalBytes authenticated target size, zero before the plan is available
     * @param detail diagnostic or status text, empty when none is available
     */
    record Snapshot(Phase phase, String version, long verifiedBytes, long totalBytes, String detail) {
        public Snapshot {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(detail, "detail");
            if (verifiedBytes < 0 || totalBytes < 0 || verifiedBytes > totalBytes) {
                throw new IllegalArgumentException("Invalid verified progress");
            }
        }
    }

    /**
     * Reads the current status without performing network or filesystem work.
     *
     * @return the latest immutable progress snapshot
     */
    Snapshot snapshot();

    /**
     * Starts or joins background verification and download of the discovered update.
     *
     * @return completion with the resulting phase; downloading does not schedule installation
     */
    CompletionStage<Snapshot> download();

    /** Requests cancellation of an active download without closing Cubism or changing installed files. */
    void cancel();
    /** Schedules application after a user-controlled exit. Must never close Cubism or discard unsaved work. */
    CompletionStage<Snapshot> installOnExit();

    /**
     * Registers for progress and immediately delivers the current snapshot on the calling thread.
     * Later callbacks run on the publishing thread; UI listeners must dispatch to the UI thread.
     *
     * @param listener non-null progress consumer
     * @return registration whose closure unregisters the consumer
     */
    Registration subscribe(Consumer<Snapshot> listener);

    @Override
    default void close() {}

    /**
     * Returns the inert service for installations without a usable self-update capability.
     *
     * @return the shared service that always reports {@link Phase#UNAVAILABLE}
     */
    static CoreFrameworkUpdates unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Inert implementation that reports unavailable and never downloads, stages or schedules an update. */
    final class Unavailable implements CoreFrameworkUpdates {
        private static final Unavailable INSTANCE = new Unavailable();
        private static final Snapshot STATE = new Snapshot(Phase.UNAVAILABLE, "", 0, 0, "");

        private Unavailable() {}

        @Override
        public Snapshot snapshot() {
            return STATE;
        }

        @Override
        public CompletionStage<Snapshot> download() {
            return CompletableFuture.completedFuture(STATE);
        }

        @Override
        public void cancel() {}

        @Override
        public CompletionStage<Snapshot> installOnExit() {
            return CompletableFuture.completedFuture(STATE);
        }

        @Override
        public Registration subscribe(final Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener").accept(STATE);
            return () -> {};
        }
    }
}
