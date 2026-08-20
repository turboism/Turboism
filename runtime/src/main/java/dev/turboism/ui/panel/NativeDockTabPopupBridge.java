package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed bridge from the exact Cubism dock-tab popup hook to runtime policy. */
public final class NativeDockTabPopupBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeDockTabPopupBridge() {
    }

    /**
     * Installs the single process-wide popup handler.
     *
     * @param handler policy invoked for each native dock-tab popup item
     * @return a registration that uninstalls this handler; closing it after another handler was
     *     installed has no effect
     * @throws NullPointerException if {@code handler} is {@code null}
     * @throws IllegalStateException if a handler is already installed
     */
    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("dock-tab popup bridge is already installed");
        }
        return () -> HANDLER.compareAndSet(requested, null);
    }

    /**
     * Entry point called from the patched Cubism dock-tab popup hook, on the host UI thread.
     *
     * <p>Fail-closed: with no handler installed, or a {@code null} argument, the native menu is
     * left exactly as the host built it. A handler that throws — including an {@link Error} — is
     * caught and logged to {@code System.err} so a broken augmentation cannot take the host popup
     * down with it.
     *
     * @param menu the native popup menu being built
     * @param palette the palette the menu belongs to
     */
    public static void afterNativeItemAppended(final Object menu, final Object palette) {
        final Handler handler = HANDLER.get();
        if (handler == null || menu == null || palette == null) {
            return;
        }
        try {
            handler.augment(menu, palette);
        } catch (Throwable failure) {
            System.err.println(
                "Turboism dock-tab popup augmentation failed safely: "
                    + failure.getClass().getName() + ": " + failure.getMessage()
            );
        }
    }

    @FunctionalInterface
    public interface Handler {
        void augment(Object menu, Object palette);
    }
}
