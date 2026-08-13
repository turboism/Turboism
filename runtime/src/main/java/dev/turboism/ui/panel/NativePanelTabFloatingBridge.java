package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.context.PanelTabSelection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-owned bridge from the Core action to the verified native panel host. */
public final class NativePanelTabFloatingBridge {

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativePanelTabFloatingBridge() {
    }

    public static void install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("panel-tab floating bridge is already installed");
        }
    }

    public static void uninstall(final Handler handler) {
        HANDLER.compareAndSet(handler, null);
    }

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
