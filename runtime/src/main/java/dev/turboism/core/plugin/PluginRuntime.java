package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.DisabledReason;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

public final class PluginRuntime {

    private final String id;
    private final PluginDescriptor descriptor;
    private PluginLifecycleState state = PluginLifecycleState.DISCOVERED;
    private TurboismPlugin instance;
    private PluginContext context;
    private DisabledReason disabledReason;

    public PluginRuntime(String id, PluginDescriptor descriptor) {
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

    public void transitionTo(PluginLifecycleState newState) {
        this.state = newState;
    }

    public TurboismPlugin instance() {
        return instance;
    }

    public void setInstance(TurboismPlugin instance) {
        this.instance = instance;
    }

    public PluginContext context() {
        return context;
    }

    public void setContext(PluginContext context) {
        this.context = context;
    }

    public DisabledReason disabledReason() {
        return disabledReason;
    }

    public void markDisabled(DisabledReason reason) {
        this.disabledReason = reason;
        this.state = PluginLifecycleState.DISABLED;
    }
}
