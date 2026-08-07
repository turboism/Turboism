package dev.turboism.adapter.cubism.backup;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;

/**
 * EDT-dispatching {@link AutoBackupAdapter} wrapper: every host operation runs
 * on the host UI thread (the native auto-backup manager is Swing-hosted), and
 * failures never escape the calling thread un-sanitized.
 */
final class VerifiedDispatchAutoBackupAdapter implements AutoBackupAdapter {

    private final AutoBackupAdapter.HostOperations host;

    VerifiedDispatchAutoBackupAdapter(final AutoBackupAdapter.HostOperations host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public AutoBackupAdapter.Snapshot settings() {
        return onEdt(() -> host.settings());
    }

    @Override
    public AutoBackupAdapter.Snapshot applySettings(final AutoBackupAdapter.Snapshot target) {
        Objects.requireNonNull(target, "target");
        return onEdt(() -> host.applySettings(target));
    }

    @Override
    public List<AutoBackupAdapter.Document> documents() {
        return onEdt(() -> host.documents());
    }

    @Override
    public void triggerBackupNow() {
        onEdt(() -> {
            host.triggerBackupNow();
            return null;
        });
    }

    private static <T> T onEdt(final Operation<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("auto-backup dispatch was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("auto-backup dispatch failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("auto-backup dispatch failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
