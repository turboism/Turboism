package dev.turboism.preview;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Per-generation load fence shared by the lifecycle worker and the awaiting caller.
 *
 * <p>The worker calls {@link #checkpoint()} between entrypoint invocations and wraps the commit
 * section (event activation, hook registration, LoadedPlugin assembly) in {@link #commit(Supplier)}.
 * The awaiting caller calls {@link #expire()} when the deadline passes. Both sides serialize on
 * this lease, so a commit that already started is allowed to finish and is then observed as a
 * success, while a commit that arrives late is fenced and unwinds through the failure path.</p>
 */
final class PluginLifecycleLease {

    private final String pluginId;
    private boolean expired;
    private boolean committed;

    PluginLifecycleLease(final String pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    /** @throws PluginLifecycleFenced when this generation was fenced after its deadline */
    void checkpoint() {
        synchronized (this) {
            if (expired) {
                throw new PluginLifecycleFenced(pluginId);
            }
        }
    }

    /**
     * Runs the commit section atomically against {@link #expire()}: either the section completes
     * and the generation is marked committed, or the fence is already up and nothing runs.
     *
     * @return the value produced by the commit section
     * @throws PluginLifecycleFenced when this generation was fenced before the commit began
     */
    <T> T commit(final Supplier<T> commitSection) {
        synchronized (this) {
            if (expired) {
                throw new PluginLifecycleFenced(pluginId);
            }
            final T value = commitSection.get();
            committed = true;
            return value;
        }
    }

    /**
     * Fences this generation unless its commit section already completed.
     *
     * @return {@code true} when the fence went up; {@code false} when the commit already won the
     *     race, in which case the caller must treat the invocation as a success
     */
    synchronized boolean expire() {
        if (committed || expired) {
            return false;
        }
        expired = true;
        return true;
    }

    synchronized boolean isExpired() {
        return expired;
    }

    synchronized boolean isCommitted() {
        return committed;
    }
}
