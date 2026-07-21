package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.DisabledReason;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;

/** Runtime state for one plugin JAR and all of its ordered entrypoints. */
public final class PluginRuntime {

    private final String id;
    private final PluginDescriptor descriptor;
    private PluginLifecycleState state = PluginLifecycleState.DISCOVERED;
    private List<TurboismPlugin> entrypoints = List.of();
    private PluginContext context;
    private DisabledReason disabledReason;

    public PluginRuntime(final String id, final PluginDescriptor descriptor) {
        this.id = id;
        this.descriptor = descriptor;
    }

    public String id() {
        return id;
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public PluginLifecycleState state() {
        return state;
    }

    public void transitionTo(final PluginLifecycleState newState) {
        this.state = newState;
    }

    public List<TurboismPlugin> entrypoints() {
        return entrypoints;
    }

    public void setEntrypoints(final List<TurboismPlugin> entrypoints) {
        this.entrypoints = List.copyOf(entrypoints);
    }

    public PluginContext context() {
        return context;
    }

    public void setContext(final PluginContext context) {
        this.context = context;
    }

    public DisabledReason disabledReason() {
        return disabledReason;
    }

    public void markDisabled(final DisabledReason reason) {
        this.disabledReason = reason;
        this.state = PluginLifecycleState.DISABLED;
    }
}
