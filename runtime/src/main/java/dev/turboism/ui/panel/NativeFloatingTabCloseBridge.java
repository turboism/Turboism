package dev.turboism.ui.panel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-open bridge from the exact floating-tab close callback to runtime policy.
 * A missing handler or an unexpected failure lets the native close proceed.
 */
public final class NativeFloatingTabCloseBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeFloatingTabCloseBridge() {
    }

    public static void install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("floating-tab close bridge is already installed");
        }
    }

    public static void uninstall(final Handler handler) {
        HANDLER.compareAndSet(handler, null);
    }

    /**
     * @param palette the native palette about to be closed by the host
     * @return {@code true} to cancel the native close (palette was docked instead)
     */
    public static boolean beforeClose(final Object palette) {
        final Handler handler = HANDLER.get();
        if (handler == null || palette == null) {
            return false;
        }
        System.err.println("Turboism floating-tab close hook fired: " + palette.getClass().getName());
        try {
            return handler.closeRequested(palette);
        } catch (Throwable failure) {
            System.err.println(
                "Turboism floating-tab close interception failed safely: "
                    + failure.getClass().getName()
            );
            return false;
        }
    }

    @FunctionalInterface
    public interface Handler {
        boolean closeRequested(Object palette);
    }
}
