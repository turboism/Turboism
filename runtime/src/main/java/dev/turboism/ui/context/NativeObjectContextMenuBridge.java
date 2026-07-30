package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed bridge from exact Cubism object-menu hooks to runtime policy. */
public final class NativeObjectContextMenuBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeObjectContextMenuBridge() {
    }

    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("object context-menu bridge is already installed");
        }
        return () -> HANDLER.compareAndSet(requested, null);
    }

    public static Object augment(
        final Object menu,
        final String locationName,
        final Object source
    ) {
        final Handler handler = HANDLER.get();
        if (handler == null || menu == null || locationName == null || source == null) {
            return menu;
        }
        try {
            final Location location = Location.valueOf(locationName);
            final Object result = handler.augment(menu, location, source);
            return result == null ? menu : result;
        } catch (Throwable failure) {
            System.err.println(
                "Turboism object context-menu augmentation failed safely: "
                    + failure.getClass().getName()
            );
            return menu;
        }
    }

    @FunctionalInterface
    public interface Handler {
        Object augment(Object menu, Location location, Object source);
    }
}
