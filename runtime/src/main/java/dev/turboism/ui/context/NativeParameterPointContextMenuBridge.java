package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Loader-neutral bridge from exact Q-menu show hooks to persistent parameter contributions. */
public final class NativeParameterPointContextMenuBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeParameterPointContextMenuBridge() {
    }

    public static dev.turboism.sdk.plugin.Registration install(final Handler handler) {
        final Handler installed = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, installed)) {
            throw new IllegalStateException("parameter-point context-menu bridge is already installed");
        }
        return () -> HANDLER.compareAndSet(installed, null);
    }

    public static void shown(final Object primaryMenu, final Object secondaryMenu, final Object context) {
        final Handler handler = HANDLER.get();
        if (handler == null || context == null) return;
        try {
            handler.shown(primaryMenu, secondaryMenu, context);
        } catch (Throwable failure) {
            // Native host callbacks fail closed.
        }
    }

    @FunctionalInterface
    public interface Handler {
        void shown(Object primaryMenu, Object secondaryMenu, Object context);
    }

    public static Handler handler(
        final VerifiedObjectContextMenuHostOperations host,
        final VerifiedObjectContextMenuNativeAccess nativeAccess
    ) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(nativeAccess, "nativeAccess");
        return (primary, secondary, context) -> {
            final Object menu = secondary == null ? primary : secondary;
            host.installPersistent(menu, Location.PARAMETER_TAB, () -> nativeAccess.resolveParameterPoint(context));
        };
    }
}
