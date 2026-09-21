package dev.turboism.sdk.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects registrations and closes them in reverse order.
 *
 * <p>Each successful {@link #register(AutoCloseable)} call creates an
 * independent entry that is released at most once, whether it is released
 * through its {@link Registration} handle or through this scope.
 */
public final class DisposableScope implements AutoCloseable {

    private final List<Entry> closeables = new ArrayList<>();
    private boolean closed = false;

    /**
     * Adds a closeable to this scope so it is closed when the scope closes.
     *
     * <p>Entries are identified by registration, not by the closeable:
     * registering equal or identical closeables still creates independent
     * entries, and closing one handle never releases another registration.
     *
     * @param closeable the resource to take ownership of
     * @return a handle that detaches this registration from the scope and
     *     closes it immediately, swallowing any failure from that close;
     *     closing the handle more than once has no further effect
     * @throws IllegalStateException when the scope has already been closed; the
     *     closeable is then not registered and not closed
     */
    public Registration register(AutoCloseable closeable) {
        Entry entry = new Entry(closeable);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("DisposableScope is already closed");
            }
            closeables.add(entry);
        }
        return new Registration() {
            @Override
            public void close() {
                if (!entry.claim()) {
                    return;
                }
                synchronized (DisposableScope.this) {
                    closeables.remove(entry);
                }
                closeSafely(entry.closeable);
            }
        };
    }

    /** Closes every claimed entry before rethrowing failures; fatal errors remain the primary cause. */
    @Override
    public void close() throws Exception {
        List<Entry> toClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(closeables);
            closeables.clear();
        }
        Throwable first = null;
        for (int i = toClose.size() - 1; i >= 0; i--) {
            Entry entry = toClose.get(i);
            if (!entry.claim()) {
                continue;
            }
            try {
                entry.closeable.close();
            } catch (Throwable failure) {
                if (first == null) {
                    first = failure;
                } else if (first != failure) {
                    if (failurePriority(failure) > failurePriority(first)) {
                        failure.addSuppressed(first);
                        first = failure;
                    } else {
                        first.addSuppressed(failure);
                    }
                }
            }
        }
        if (first instanceof Error fatal) throw fatal;
        if (first instanceof Exception exception) throw exception;
        if (first != null) throw new IllegalStateException("Scope cleanup failed", first);
    }

    private static int failurePriority(final Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) return 2;
        return failure instanceof Error ? 1 : 0;
    }

    private static void closeSafely(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * One registration's shared release ownership between the scope and its
     * handle. The claim is taken before user close code runs so that
     * concurrent or reentrant close paths release the entry exactly once.
     */
    private static final class Entry {
        private final AutoCloseable closeable;
        private boolean claimed;

        Entry(AutoCloseable closeable) {
            this.closeable = closeable;
        }

        synchronized boolean claim() {
            if (claimed) {
                return false;
            }
            claimed = true;
            return true;
        }
    }
}
