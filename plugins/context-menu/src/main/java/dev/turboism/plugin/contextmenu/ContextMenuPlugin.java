package dev.turboism.plugin.contextmenu;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** SDK-only migration shell; it intentionally contributes no host capability or UI. */
public final class ContextMenuPlugin implements TurboismPlugin {

    private PluginContext context;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Context Menu migration shell initialized");
    }

    @Override
    public void enable() {
        requireContext();
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public void shutdown() {
        enabled = false;
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Context Menu migration shell must be initialized before enable.");
        }
    }
}
