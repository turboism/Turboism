package dev.turboism.cleanup;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe, plugin-scoped accounting of completed runtime cleanup outcomes. */
public final class CleanupEvidenceCollector {

    private final LongAdder taskHandlesCanceled = new LongAdder();
    private final LongAdder taskCompletionsSettled = new LongAdder();
    private final LongAdder pluginContinuationsDrained = new LongAdder();
    private final LongAdder userFileHandlesRevoked = new LongAdder();
    private final LongAdder configSchemasUnregistered = new LongAdder();
    private final LongAdder temporaryFilesDeleted = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public void taskHandleCanceled() {
        taskHandlesCanceled.increment();
    }

    public void taskCompletionSettled() {
        taskCompletionsSettled.increment();
    }

    public void pluginContinuationDrained() {
        pluginContinuationsDrained.increment();
    }

    public void userFileHandleRevoked() {
        userFileHandlesRevoked.increment();
    }

    public void configSchemasUnregistered(final long count) {
        if (count > 0) {
            configSchemasUnregistered.add(count);
        }
    }

    public void temporaryFileDeleted() {
        temporaryFilesDeleted.increment();
    }

    public void cleanupFailed() {
        failures.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            taskHandlesCanceled.sum(),
            taskCompletionsSettled.sum(),
            pluginContinuationsDrained.sum(),
            userFileHandlesRevoked.sum(),
            configSchemasUnregistered.sum(),
            temporaryFilesDeleted.sum(),
            failures.sum()
        );
    }

    public record Snapshot(
        long taskHandlesCanceled,
        long taskCompletionsSettled,
        long pluginContinuationsDrained,
        long userFileHandlesRevoked,
        long configSchemasUnregistered,
        long temporaryFilesDeleted,
        long failures
    ) {
        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
