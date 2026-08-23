package dev.turboism.sdk.task;

import dev.turboism.sdk.plugin.CancellationToken;

/**
 * The unit of work a plugin hands to the task scheduler.
 *
 * <p>Runs on a scheduler-owned worker thread, never on the Cubism host thread; an implementation
 * that needs to touch Editor state must hop back to the host thread itself. Implementations are
 * expected to poll the supplied token and return early once cancellation is requested, since the
 * scheduler cannot interrupt work that ignores it.
 */
@FunctionalInterface
public interface PluginTaskAction {
    /**
     * Performs the work.
     *
     * @param cancellationToken token the action should poll so a cancelled task stops promptly
     * @throws Exception any failure; the scheduler records it as a failed run rather than
     *     propagating it to the caller that submitted the task
     */
    void run(CancellationToken cancellationToken) throws Exception;
}
