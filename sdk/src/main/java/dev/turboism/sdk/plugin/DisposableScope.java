package dev.turboism.sdk.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects registrations and closes them in reverse order.
 */
public final class DisposableScope implements AutoCloseable {

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private boolean closed = false;

    /**
     * Adds a closeable to this scope so it is closed when the scope closes.
     *
     * @param closeable the resource to take ownership of
     * @return a handle that detaches the closeable from this scope and closes it
     *     immediately, swallowing any failure from that close
     * @throws IllegalStateException when the scope has already been closed; the
     *     closeable is then not registered and not closed
     */
    public Registration register(AutoCloseable closeable) {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("DisposableScope is already closed");
            }
            closeables.add(closeable);
            return new Registration() {
                @Override
                public void close() {
                    synchronized (DisposableScope.this) {
                        closeables.remove(closeable);
                    }
                    closeSafely(closeable);
                }
            };
        }
    }

    @Override
    public void close() throws Exception {
        List<AutoCloseable> toClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(closeables);
            closeables.clear();
        }
        Exception first = null;
        for (int i = toClose.size() - 1; i >= 0; i--) {
            try {
                toClose.get(i).close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void closeSafely(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
