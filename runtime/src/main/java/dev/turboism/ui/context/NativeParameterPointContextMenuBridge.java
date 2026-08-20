package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Loader-neutral bridge from exact Q-menu show hooks to persistent parameter contributions. */
public final class NativeParameterPointContextMenuBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeParameterPointContextMenuBridge() {
    }

    /**
     * Installs the single process-wide handler for parameter-point menu-shown notifications.
     *
     * <p>Only one handler may be installed at a time. Closing the returned registration clears
     * the handler only if it is still the one this call installed.
     *
     * @param handler the handler notified when a parameter-point menu is shown; must not be null
     * @return a registration that uninstalls this handler
     * @throws IllegalStateException if a handler is already installed
     * @throws NullPointerException if {@code handler} is null
     */
    public static dev.turboism.sdk.plugin.Registration install(final Handler handler) {
        final Handler installed = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, installed)) {
            throw new IllegalStateException("parameter-point context-menu bridge is already installed");
        }
        return () -> HANDLER.compareAndSet(installed, null);
    }

    /**
     * The native entry point, called by the host when a parameter-point menu is shown.
     *
     * <p>Fail-closed: does nothing when no handler is installed or the context is null, and
     * swallows anything the handler throws so no failure crosses back into host code. The menu
     * arguments are passed through untouched, including when either is null.
     *
     * @param primaryMenu the host's primary menu object, may be null
     * @param secondaryMenu the host's secondary menu object, may be null
     * @param context the host parameter-point context; nothing happens when null
     */
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

    /**
     * Builds the handler that contributes persistent entries to whichever menu the host shows.
     *
     * <p>Prefers the secondary menu when the host supplies one, falling back to the primary.
     * Entries are installed at {@code Location.PARAMETER_TAB}, and the selection is resolved
     * lazily from the context each time it is needed rather than captured up front.
     *
     * @param host the host operations that perform the persistent installation; must not be null
     * @param nativeAccess resolves the parameter point from the host context; must not be null
     * @return a handler suitable for {@link #install}
     * @throws NullPointerException if either argument is null
     */
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
