package dev.turboism.shell;

import dev.turboism.internal.core.CorePluginManagement;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JButton;

/** Explicit review/consent on EDT; snapshot and cancellation IO on one owned worker. */
final class PendingPluginChangesAction implements AutoCloseable {
    private final CorePluginManagement plugins;
    private final Predicate<CorePluginManagement.PendingChanges> confirm;
    private final Consumer<String> status;
    private final Runnable refresh;
    private final ExecutorService worker;
    private final JButton button;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean busy; // Remaining view state is EDT-owned.
    private boolean committing;
    private long generation;

    PendingPluginChangesAction(
            final String label,
            final CorePluginManagement plugins,
            final Predicate<CorePluginManagement.PendingChanges> confirm,
            final Consumer<String> status,
            final Runnable refresh) {
        this(label, plugins, confirm, status, refresh, Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "turboism-pending-plugin-changes");
            thread.setDaemon(true);
            return thread;
        }));
    }

    PendingPluginChangesAction(
            final String label,
            final CorePluginManagement plugins,
            final Predicate<CorePluginManagement.PendingChanges> confirm,
            final Consumer<String> status,
            final Runnable refresh,
            final ExecutorService worker) {
        this.plugins = Objects.requireNonNull(plugins);
        this.confirm = Objects.requireNonNull(confirm);
        this.status = Objects.requireNonNull(status);
        this.refresh = Objects.requireNonNull(refresh);
        this.worker = Objects.requireNonNull(worker);
        button = new JButton(label);
        button.setName("plugins.pending.review");
        button.addActionListener(ignored -> review());
    }

    JButton button() {
        return button;
    }

    /** Hiding/reopening the owner invalidates reads, but never already-consented commits. */
    void viewHidden() {
        if (closed.get() || committing) return;
        generation++;
        busy = false;
        button.setEnabled(true);
    }

    private void review() {
        if (closed.get() || busy) return;
        final long request = ++generation;
        busy = true;
        button.setEnabled(false);
        status.accept("PLUGIN_PENDING_LOADING");
        try {
            worker.execute(() -> {
                final CorePluginManagement.PendingChanges snapshot;
                try {
                    snapshot = plugins.pendingChanges();
                } catch (RuntimeException failure) {
                    CoreDialogs.onEdt(() -> finish(request, "PLUGIN_PENDING_RECOVERY_REQUIRED"));
                    return;
                }
                CoreDialogs.onEdt(() -> consent(request, snapshot));
            });
        } catch (RejectedExecutionException unavailable) {
            finish(request, "PLUGIN_PENDING_UNAVAILABLE");
        }
    }

    private void consent(final long request, final CorePluginManagement.PendingChanges snapshot) {
        if (closed.get() || request != generation) return;
        if (snapshot == null || !("PLUGIN_PENDING_READY".equals(snapshot.code())
                || "PLUGIN_PENDING_FAILED".equals(snapshot.code()))) {
            finish(request, snapshot == null ? "PLUGIN_PENDING_RECOVERY_REQUIRED" : snapshot.code());
            return;
        }
        if (snapshot.snapshot().isBlank() || snapshot.changes().isEmpty()) {
            finish(request, "PLUGIN_PENDING_RECOVERY_REQUIRED");
            return;
        }
        final boolean accepted;
        try {
            accepted = confirm.test(snapshot);
        } catch (RuntimeException failedDialog) {
            finish(request, "PLUGIN_PENDING_UNAVAILABLE");
            return;
        }
        if (closed.get() || request != generation) return;
        if (!accepted) {
            finish(request, "PLUGIN_PENDING_UNCHANGED");
            return;
        }
        committing = true;
        status.accept("PLUGIN_PENDING_DISCARDING");
        try {
            worker.execute(() -> {
                final String code;
                try {
                    code = plugins.discardPendingChanges(snapshot.snapshot()).code();
                } catch (RuntimeException failure) {
                    CoreDialogs.onEdt(() -> finish(request, "PLUGIN_PENDING_RECOVERY_REQUIRED"));
                    return;
                }
                CoreDialogs.onEdt(() -> {
                    if (closed.get() || request != generation) return;
                    finish(request, code);
                    refresh.run();
                });
            });
        } catch (RejectedExecutionException unavailable) {
            finish(request, "PLUGIN_PENDING_UNAVAILABLE");
        }
    }

    private void finish(final long request, final String code) {
        if (closed.get() || request != generation) return;
        busy = false;
        committing = false;
        button.setEnabled(true);
        status.accept(code);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Closing a view must not interrupt already-consented journal publication.
        worker.shutdown();
    }
}
