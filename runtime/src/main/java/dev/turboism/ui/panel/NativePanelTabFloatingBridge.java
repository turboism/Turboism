package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.context.PanelTabSelection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-owned bridge from the Core action to the verified native panel host. */
public final class NativePanelTabFloatingBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativePanelTabFloatingBridge() {
    }

    /**
     * Installs the single process-wide handler that performs the float/dock toggle against the
     * verified native panel host.
     *
     * @param handler the host-specific toggle implementation
     * @throws NullPointerException if {@code handler} is {@code null}
     * @throws IllegalStateException if a handler is already installed
     */
    public static void install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("panel-tab floating bridge is already installed");
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
     * Toggles the selected panel tab between docked and floating.
     *
     * <p>Unlike the fail-open close bridge, this fails loudly: with no verified host installed
     * there is no safe fallback, so the caller is told the action is unavailable rather than
     * silently doing nothing.
     *
     * @param selection the panel tab to toggle
     * @throws IllegalStateException if no handler is installed
     * @throws NullPointerException if {@code selection} is {@code null}
     */
    public static void toggle(final PanelTabSelection selection) {
        final Handler handler = HANDLER.get();
        if (handler == null) {
            throw new IllegalStateException("panel-tab floating action is unavailable");
        }
        handler.toggle(Objects.requireNonNull(selection, "selection"));
    }

    @FunctionalInterface
    public interface Handler {
        void toggle(PanelTabSelection selection);
    }
}
