package dev.turboism.internal.core;

import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Internal UI boundary for installing framework updates. No filesystem or network work on the UI thread. */
public interface CoreFrameworkUpdates extends AutoCloseable {
    enum Phase { UNAVAILABLE, IDLE, DOWNLOADING, STAGED, APPLY_PENDING, CANCELLED, FAILED }

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

    Snapshot snapshot();
    CompletionStage<Snapshot> download();
    void cancel();
    /** Schedules application after a user-controlled exit. Must never close Cubism or discard unsaved work. */
    CompletionStage<Snapshot> installOnExit();
    Registration subscribe(Consumer<Snapshot> listener);

    @Override default void close() {}

    static CoreFrameworkUpdates unavailable() { return Unavailable.INSTANCE; }

    final class Unavailable implements CoreFrameworkUpdates {
        private static final Unavailable INSTANCE = new Unavailable();
        private static final Snapshot STATE = new Snapshot(Phase.UNAVAILABLE, "", 0, 0, "");
        private Unavailable() {}
        public Snapshot snapshot() { return STATE; }
        public CompletionStage<Snapshot> download() { return CompletableFuture.completedFuture(STATE); }
        public void cancel() {}
        public CompletionStage<Snapshot> installOnExit() { return CompletableFuture.completedFuture(STATE); }
        public Registration subscribe(final Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener").accept(STATE);
            return () -> {};
        }
    }
}
