package dev.turboism.ui.toolbar;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Bounded native-widget identity observation. Runs only while a toolbar provider is applied. */
final class ToolbarLifecycleWatch implements Registration {
    private final Supplier<?> identity;
    private final BooleanSupplier detached;
    private final Runnable reconcile;
    private final Timer timer;
    private final PropertyChangeListener appearance;
    private Object previous;
    private boolean closed;
    private boolean appearanceQueued;

    ToolbarLifecycleWatch(Supplier<?> identity, BooleanSupplier detached, Runnable reconcile) {
        this.identity = identity;
        this.detached = detached;
        this.reconcile = reconcile;
        previous = identity.get();
        timer = new Timer(250, ignored -> poll());
        appearance = event -> {
            if ("lookAndFeel".equals(event.getPropertyName())) {
                SwingUtilities.invokeLater(this::appearanceChanged);
            }
        };
    }

    void start() {
        timer.start();
        UIManager.addPropertyChangeListener(appearance);
    }

    void poll() {
        if (closed) return;
        try {
            final Object next = identity.get();
            if (!Objects.equals(previous, next) || detached.getAsBoolean()) {
                reconcile.run();
                previous = identity.get();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A closing/replacing native frame may be temporarily unavailable. Never mutate a
            // guessed replacement; the next bounded observation or provider disposal resolves it.
        }
    }

    void appearanceChanged() {
        if (closed || appearanceQueued) return;
        appearanceQueued = true;
        SwingUtilities.invokeLater(() -> {
            appearanceQueued = false;
            if (closed) return;
            try {
                reconcile.run();
                previous = identity.get();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
            }
        });
    }

    @Override
    public void close() {
        EdtDispatch.runEventually("main-toolbar observer removal", () -> {
            if (closed) return;
            closed = true;
            timer.stop();
            UIManager.removePropertyChangeListener(appearance);
        });
    }
}
