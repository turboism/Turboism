package dev.turboism.ui.panel;

import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed bridge from a verified palette-frame disposal hook to runtime cleanup. */
public final class NativeFloatingFrameDisposeBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeFloatingFrameDisposeBridge() {
    }

    public static void install(final Handler handler) {
        if (!HANDLER.compareAndSet(null, handler)) {
            throw new IllegalStateException("floating-frame dispose bridge is already installed");
        }
    }

    public static void uninstall(final Handler handler) {
        HANDLER.compareAndSet(handler, null);
    }

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
