package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed bridge from the exact Cubism dock-tab popup hook to runtime policy. */
public final class NativeDockTabPopupBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeDockTabPopupBridge() {
    }

    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("dock-tab popup bridge is already installed");
        }
        return () -> HANDLER.compareAndSet(requested, null);
    }

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
