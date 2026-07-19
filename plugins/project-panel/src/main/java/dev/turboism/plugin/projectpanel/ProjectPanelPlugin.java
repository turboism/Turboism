package dev.turboism.plugin.projectpanel;

import dev.turboism.plugin.projectpanel.b1.application.ProjectPanelStateBinding;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** SDK-only migration shell; it intentionally contributes no host capability or UI. */
public final class ProjectPanelPlugin implements TurboismPlugin {

    private ProjectPanelStateBinding b1Application = new ProjectPanelStateBinding();
    private PluginContext context;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        b1Application.init(context.config());
        context.logger().info("Project Panel migration shell initialized");
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
            throw new IllegalStateException("Project Panel migration shell must be initialized before enable.");
        }
    }
}
