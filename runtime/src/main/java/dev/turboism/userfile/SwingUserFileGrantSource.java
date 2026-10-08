package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import java.awt.KeyboardFocusManager;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Interactive grant source; only the runtime receives the selected path. */
public final class SwingUserFileGrantSource implements UserFileGrantSource, AutoCloseable {
    private final Supplier<JFileChooser> factory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<Decision>> pending = ConcurrentHashMap.newKeySet();
    private JFileChooser visible; // EDT only, including modal event-loop reentry

    public SwingUserFileGrantSource() {
        this(JFileChooser::new);
    }

    SwingUserFileGrantSource(final Supplier<JFileChooser> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public CompletionStage<Decision> request(final UserFileRequest request) {
        Objects.requireNonNull(request, "request");
        final CompletableFuture<Decision> result = new CompletableFuture<>();
        pending.add(result);
        if (closed.get()) {
            finish(result, Decision.unavailable());
        } else {
            SwingUtilities.invokeLater(() -> choose(request, result));
        }
        return result.minimalCompletionStage();
    }

    private void choose(final UserFileRequest request, final CompletableFuture<Decision> result) {
        if (closed.get() || visible != null) {
            finish(result, Decision.unavailable());
            return;
        }
        try {
            final JFileChooser chooser = factory.get();
            visible = chooser;
            chooser.setDialogTitle(request.title());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setMultiSelectionEnabled(false);
            if (!request.allowedExtensions().isEmpty()) {
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileFilter(new FileNameExtensionFilter(
                    String.join(", ", request.allowedExtensions()),
                    request.allowedExtensions().toArray(String[]::new)));
            }
            if (closed.get()) {
                finish(result, Decision.unavailable());
                return;
            }
            final var owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            final int answer = request.mode() == UserFileMode.READ
                ? chooser.showOpenDialog(owner) : chooser.showSaveDialog(owner);
            final Decision decision;
            if (closed.get() || answer == JFileChooser.ERROR_OPTION) {
                decision = Decision.unavailable();
            } else if (answer != JFileChooser.APPROVE_OPTION) {
                decision = Decision.canceled();
            } else if (chooser.getSelectedFile() == null
                || !chooser.getFileFilter().accept(chooser.getSelectedFile())
                || chooser.getSelectedFile().isDirectory()) {
                decision = Decision.unavailable();
            } else {
                decision = Decision.selected(chooser.getSelectedFile().toPath());
            }
            finish(result, decision);
        } catch (RuntimeException failure) {
            finish(result, Decision.unavailable());
        } finally {
            visible = null;
        }
    }

    private void finish(final CompletableFuture<Decision> result, final Decision decision) {
        pending.remove(result);
        result.complete(closed.get() ? Decision.unavailable() : decision);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(result -> finish(result, Decision.unavailable()));
        final Runnable cancel = () -> {
            if (visible != null) visible.cancelSelection();
        };
        if (SwingUtilities.isEventDispatchThread()) cancel.run();
        else SwingUtilities.invokeLater(cancel);
    }
}
