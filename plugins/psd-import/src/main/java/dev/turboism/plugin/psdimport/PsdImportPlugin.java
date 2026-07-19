package dev.turboism.plugin.psdimport;

import dev.turboism.plugin.psdimport.b1.application.PsdActionApplication;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** SDK-only migration shell; it intentionally contributes no host capability or UI. */
public final class PsdImportPlugin implements TurboismPlugin {

    private PsdActionApplication b1Application = new PsdActionApplication();
    private PluginContext context;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("PSD Import migration shell initialized");
    }

    @Override
    public void enable() {
        b1Application.enable();
        requireContext();
        enabled = true;
    }

    @Override
    public void disable() {
        b1Application.disable();
        enabled = false;
    }

    @Override
    public void shutdown() {
        b1Application.shutdown();
        enabled = false;
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("PSD Import migration shell must be initialized before enable.");
        }
    }
}
