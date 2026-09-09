package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AWTError;
import java.io.File;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Runtime-owned Swing chooser-backed grant source for one plugin scope. */
public final class SwingUserFileGrantSource implements UserFileGrantSource, AutoCloseable {

    @FunctionalInterface
    interface EdtDispatcher {
        void dispatch(Runnable action);
    }

    /** Always queues the chooser work, including when the request starts on the EDT. */
    private static final EdtDispatcher EDT = SwingUtilities::invokeLater;

    private final Object lifecycleLock = new Object();
    private final Supplier<JFileChooser> chooserFactory;
    private final EdtDispatcher edt;
    private boolean active = true;
    private PendingRequest pending;
    private VisibleChooser visible;

    public SwingUserFileGrantSource() {
        this(JFileChooser::new, EDT);
    }

    SwingUserFileGrantSource(
        final Supplier<JFileChooser> chooserFactory,
        final EdtDispatcher edt
    ) {
        this.chooserFactory = Objects.requireNonNull(chooserFactory, "chooserFactory");
        this.edt = Objects.requireNonNull(edt, "edt");
    }

    @Override
    public CompletionStage<Decision> request(final UserFileRequest request) {
        final UserFileRequest requested = Objects.requireNonNull(request, "request");
        final PendingRequest candidate = new PendingRequest(requested);
        synchronized (lifecycleLock) {
            if (!active || pending != null) {
                return CompletableFuture.completedFuture(Decision.unavailable());
            }
            pending = candidate;
        }
        try {
            edt.dispatch(() -> show(candidate));
        } catch (RuntimeException | LinkageError | AWTError failure) {
            settle(candidate, Decision.unavailable());
        }
        return candidate.completion;
    }

    @Override
    public void close() {
        final PendingRequest terminal;
        final JFileChooser chooser;
        synchronized (lifecycleLock) {
            if (!active) {
                return;
            }
            active = false;
            terminal = pending;
            pending = null;
            chooser = visible == null ? null : visible.chooser();
            visible = null;
        }
        if (terminal != null) {
            terminal.completion.complete(Decision.unavailable());
        }
        if (chooser != null) {
            try {
                edt.dispatch(() -> cancelOnEdt(chooser));
            } catch (RuntimeException | LinkageError | AWTError failure) {
                // The request is already fenced unavailable; close must remain non-blocking.
            }
        }
    }

    private void show(final PendingRequest expected) {
        if (!isPending(expected)) {
            return;
        }
        try {
            final JFileChooser chooser = Objects.requireNonNull(
                chooserFactory.get(),
                "chooserFactory.get()"
            );
            synchronized (lifecycleLock) {
                if (!active || pending != expected) {
                    return;
                }
                visible = new VisibleChooser(expected, chooser);
            }
            configure(chooser, expected.request());
            final int choice = expected.request().mode() == UserFileMode.READ
                ? chooser.showOpenDialog(null)
                : chooser.showSaveDialog(null);
            settle(
                expected,
                choice == JFileChooser.APPROVE_OPTION
                    ? approvedDecision(chooser.getSelectedFile())
                    : Decision.canceled()
            );
        } catch (RuntimeException | LinkageError | AWTError failure) {
            settle(expected, Decision.unavailable());
        } finally {
            synchronized (lifecycleLock) {
                if (visible != null && visible.owner() == expected) {
                    visible = null;
                }
            }
        }
    }

    private void cancelOnEdt(final JFileChooser chooser) {
        try {
            chooser.cancelSelection();
        } catch (RuntimeException | LinkageError | AWTError failure) {
            // The request is already unavailable; cancellation is best-effort only.
        }
    }

    private boolean isPending(final PendingRequest expected) {
        synchronized (lifecycleLock) {
            return active && pending == expected;
        }
    }

    private void settle(final PendingRequest expected, final Decision decision) {
        final boolean current;
        synchronized (lifecycleLock) {
            current = active && pending == expected;
            if (visible != null && visible.owner() == expected) {
                visible = null;
            }
            if (current) {
                pending = null;
            }
        }
        if (current) {
            expected.completion.complete(Objects.requireNonNull(decision, "decision"));
        }
    }

    private static void configure(
        final JFileChooser chooser,
        final UserFileRequest request
    ) {
        chooser.setDialogTitle(request.title());
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.resetChoosableFileFilters();
        if (request.allowedExtensions().isEmpty()) {
            chooser.setAcceptAllFileFilterUsed(true);
            return;
        }
        chooser.setAcceptAllFileFilterUsed(false);
        final String description = request.allowedExtensions().stream()
            .map(extension -> "*." + extension)
            .collect(Collectors.joining(", "));
        chooser.setFileFilter(new FileNameExtensionFilter(
            description,
            request.allowedExtensions().toArray(new String[0])
        ));
    }

    private static Decision approvedDecision(final File selected) {
        return selected == null ? Decision.unavailable() : Decision.selected(selected.toPath());
    }

    private static final class PendingRequest {
        private final UserFileRequest request;
        private final CompletableFuture<Decision> completion = new CompletableFuture<>();

        private PendingRequest(final UserFileRequest request) {
            this.request = request;
        }

        private UserFileRequest request() {
            return request;
        }
    }

    private record VisibleChooser(PendingRequest owner, JFileChooser chooser) {
        private VisibleChooser {
            owner = Objects.requireNonNull(owner, "owner");
            chooser = Objects.requireNonNull(chooser, "chooser");
        }
    }
}
