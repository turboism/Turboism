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

    /** Records that one plugin task handle was cancelled during teardown. */
    public void taskHandleCanceled() {
        taskHandlesCanceled.increment();
    }

    /** Records that one outstanding task completion was settled rather than left pending. */
    public void taskCompletionSettled() {
        taskCompletionsSettled.increment();
    }

    /** Records that one queued plugin continuation was drained instead of dropped. */
    public void pluginContinuationDrained() {
        pluginContinuationsDrained.increment();
    }

    /** Records that one granted user-file handle was revoked. */
    public void userFileHandleRevoked() {
        userFileHandlesRevoked.increment();
    }

    /**
     * Records that a plugin's configuration schemas were unregistered.
     *
     * @param count how many schemas were removed
     */
    public void configSchemasUnregistered(final long count) {
        if (count > 0) {
            configSchemasUnregistered.add(count);
        }
    }

    /** Records that one runtime-owned temporary file was deleted. */
    public void temporaryFileDeleted() {
        temporaryFilesDeleted.increment();
    }

    /** Records that one cleanup step failed, so a partial teardown stays observable. */
    public void cleanupFailed() {
        failures.increment();
    }

    /**
     * Takes a consistent-enough point-in-time reading of every counter.
     *
     * <p>Counters are summed independently, so a snapshot taken while cleanup is still
     * running can mix readings from either side of an increment. It is evidence for a
     * completed teardown, not a transactional view.</p>
     *
     * @return the current counts
     */
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

    /**
     * Immutable counts of completed cleanup outcomes for one plugin.
     *
     * @param taskHandlesCanceled task handles cancelled during teardown
     * @param taskCompletionsSettled outstanding completions settled rather than left pending
     * @param pluginContinuationsDrained queued continuations drained instead of dropped
     * @param userFileHandlesRevoked granted user-file handles revoked
     * @param configSchemasUnregistered configuration schemas removed
     * @param temporaryFilesDeleted runtime-owned temporary files deleted
     * @param failures cleanup steps that failed
     */
    public record Snapshot(
        long taskHandlesCanceled,
        long taskCompletionsSettled,
        long pluginContinuationsDrained,
        long userFileHandlesRevoked,
        long configSchemasUnregistered,
        long temporaryFilesDeleted,
        long failures
    ) {
        /**
         * Returns an all-zero snapshot.
         *
         * @return a snapshot for a plugin that never needed cleanup
         */
        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
