package dev.turboism.plugin.parametersync.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-local re-entrancy guard that tracks parameters undergoing active synchronization
 * to prevent recursion loops, cascade feedback, and stack overflows.
 */
public final class SyncExecutionGuard {

    private final ThreadLocal<Set<String>> activeSyncing = ThreadLocal.withInitial(HashSet::new);

    /**
     * Checks whether an event originating from {@code parameterId} is allowed to trigger a sync.
     * Returns false if the parameter is currently being modified by an ongoing sync operation.
     */
    public boolean canSyncFrom(final String parameterId) {
        if (parameterId == null) {
            return false;
        }
        return !activeSyncing.get().contains(parameterId);
    }

    /**
     * Executes the given action while marking the provided parameter IDs as active,
     * ensuring any secondary events fired for these parameters are suppressed.
     */
    public void executeGuarded(final Collection<String> participatingIds, final Runnable action) {
        Objects.requireNonNull(participatingIds, "participatingIds");
        Objects.requireNonNull(action, "action");
        final Set<String> active = activeSyncing.get();
        final Set<String> newlyAdded = new HashSet<>();
        for (String id : participatingIds) {
            if (active.add(id)) {
                newlyAdded.add(id);
            }
        }
        try {
            action.run();
        } finally {
            active.removeAll(newlyAdded);
        }
    }

    /**
     * Returns the currently active parameters in this thread for testing and diagnostics.
     */
    public Set<String> activeParameters() {
        return Collections.unmodifiableSet(new HashSet<>(activeSyncing.get()));
    }
}
