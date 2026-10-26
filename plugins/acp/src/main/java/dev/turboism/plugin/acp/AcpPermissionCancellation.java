package dev.turboism.plugin.acp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Local permission lifetime; cancellation does not assert that a host operation was cancelled. */
final class AcpPermissionCancellation {
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private boolean cancelled;
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    synchronized boolean isCancelled() {
        return cancelled;
    }

    Registration onCancel(final Runnable listener) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return () -> {
                    synchronized (this) {
                        listeners.remove(listener);
                    }
                };
            }
        }
        listener.run();
        return () -> {};
    }

    void cancel() {
        final ArrayList<Runnable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (var listener : pending) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                /* Other permission owners must still be released. */
            }
        }
    }
}
