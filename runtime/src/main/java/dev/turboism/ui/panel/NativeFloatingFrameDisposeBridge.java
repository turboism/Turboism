package dev.turboism.ui.panel;

import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed bridge from a verified palette-frame disposal hook to runtime cleanup. */
public final class NativeFloatingFrameDisposeBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeFloatingFrameDisposeBridge() {
    }

    /**
     * Installs the single process-wide disposal handler.
     *
     * @param handler cleanup invoked after a palette frame is disposed
     * @throws IllegalStateException if a handler is already installed
     */
    public static void install(final Handler handler) {
        if (!HANDLER.compareAndSet(null, handler)) {
            throw new IllegalStateException("floating-frame dispose bridge is already installed");
        }
    }

    /**
     * Removes {@code handler} if it is the currently installed one; otherwise does nothing, so a
     * late uninstall cannot detach a successor's handler.
     *
     * @param handler the handler to remove
     */
    public static void uninstall(final Handler handler) {
        HANDLER.compareAndSet(handler, null);
    }

    /**
     * Entry point called from the verified frame-disposal hook, on the host UI thread.
     *
     * <p>Fail-closed: no handler or a {@code null} frame means no cleanup runs, and a handler
     * that throws — including an {@link Error} — is caught and logged to {@code System.err}
     * rather than propagating into host disposal.
     *
     * @param frame the frame that was disposed
     */
    public static void afterDispose(final Object frame) {
        final Handler handler = HANDLER.get();
        if (handler == null || frame == null) {
            return;
        }
        System.err.println("Turboism floating-frame dispose hook fired: " + frame.getClass().getName());
        try {
            handler.disposed(frame);
        } catch (Throwable failure) {
            System.err.println(
                "Turboism floating-frame cleanup failed safely: " + failure.getClass().getName()
                    + ": " + failure.getMessage()
            );
        }
    }

    @FunctionalInterface
    public interface Handler {
        void disposed(Object frame);
    }
}
