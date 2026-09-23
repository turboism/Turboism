package dev.turboism.preview;

import java.time.Duration;
import java.util.Objects;

/**
 * Deadlines and bounds for the shared plugin lifecycle lane.
 *
 * <p>The lane runs construct/init/enable and disable/shutdown bodies for one plugin per task on a
 * small fixed worker pool with a bounded queue. A task that outlives its deadline is fenced: the
 * generation stops admitting new work, late completions cannot commit, and resources are retained
 * until the worker actually exits and admitted callbacks quiesce.</p>
 *
 * @param workerCount lane worker threads; bounds how many stuck plugins can be absorbed at once
 * @param queueCapacity bounded pending-task admission; saturation fails fast instead of queueing
 *     unboundedly
 * @param loadTimeout deadline for one plugin's construct/init/enable sequence
 * @param cleanupTimeout deadline the caller waits for rollback of a failed load before deferring
 *     the rest to retention
 * @param closeTimeout deadline for one plugin's disable/shutdown/unload sequence during close
 * @param eventQuiescenceTimeout bounded wait for admitted event callbacks to quiesce inside one
 *     lifecycle task before its generation is deferred to retention
 * @param retentionRetryInterval minimum delay between retained-generation cleanup attempts that
 *     need to be re-driven (for example backup quiescence retries)
 */
public record PluginLifecyclePolicy(
    int workerCount,
    int queueCapacity,
    Duration loadTimeout,
    Duration cleanupTimeout,
    Duration closeTimeout,
    Duration eventQuiescenceTimeout,
    Duration retentionRetryInterval
) {
    public PluginLifecyclePolicy {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        Objects.requireNonNull(loadTimeout, "loadTimeout");
        Objects.requireNonNull(cleanupTimeout, "cleanupTimeout");
        Objects.requireNonNull(closeTimeout, "closeTimeout");
        Objects.requireNonNull(eventQuiescenceTimeout, "eventQuiescenceTimeout");
        Objects.requireNonNull(retentionRetryInterval, "retentionRetryInterval");
        if (loadTimeout.isNegative() || loadTimeout.isZero()) {
            throw new IllegalArgumentException("loadTimeout must be positive");
        }
        if (cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            throw new IllegalArgumentException("cleanupTimeout must be positive");
        }
        if (closeTimeout.isNegative() || closeTimeout.isZero()) {
            throw new IllegalArgumentException("closeTimeout must be positive");
        }
        if (eventQuiescenceTimeout.isNegative()) {
            throw new IllegalArgumentException("eventQuiescenceTimeout must not be negative");
        }
        if (retentionRetryInterval.isNegative()) {
            throw new IllegalArgumentException("retentionRetryInterval must not be negative");
        }
    }

    /** Production bounds: a slow plugin is fenced rather than blocking startup or shutdown. */
    public static PluginLifecyclePolicy production() {
        return new PluginLifecyclePolicy(
            4,
            128,
            Duration.ofSeconds(30),
            Duration.ofSeconds(15),
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            Duration.ofSeconds(1)
        );
    }
}
