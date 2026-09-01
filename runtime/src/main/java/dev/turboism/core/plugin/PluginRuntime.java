package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.DisabledReason;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;

/**
 * Runtime state for one plugin JAR and all of its ordered entrypoints.
 *
 * <p>Mutable lifecycle composition is owned by the plugin lifecycle, which drives every transition
 * from a single thread. The current state is published for observers awaiting asynchronous lifecycle
 * completion. Entrypoint order is the declaration order from the plugin manifest and is preserved,
 * because hook dispatch is order-sensitive.</p>
 */
public final class PluginRuntime {

    private final String id;
    private final PluginDescriptor descriptor;
    private volatile PluginLifecycleState state = PluginLifecycleState.DISCOVERED;
    private List<TurboismPlugin> entrypoints = List.of();
    private PluginContext context;
    private DisabledReason disabledReason;

    /**
     * Creates runtime state for a discovered plugin.
     *
     * @param id the plugin's declared identity
     * @param descriptor the validated plugin manifest
     */
    public PluginRuntime(final String id, final PluginDescriptor descriptor) {
        this.id = id;
        this.descriptor = descriptor;
    }

    /**
     * Returns the plugin's declared identity.
     *
     * @return the plugin id
     */
    public String id() {
        return id;
    }

    /**
     * Returns the validated plugin manifest.
     *
     * @return the plugin descriptor
     */
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return the lifecycle state, {@code DISCOVERED} until the loader advances it
     */
    public PluginLifecycleState state() {
        return state;
    }

    /**
     * Advances the lifecycle state.
     *
     * @param newState the state the lifecycle has reached
     */
    public void transitionTo(final PluginLifecycleState newState) {
        this.state = newState;
    }

    /**
     * Returns the plugin's entrypoints in manifest declaration order.
     *
     * @return an immutable list, empty until the entrypoints are instantiated
     */
    public List<TurboismPlugin> entrypoints() {
        return entrypoints;
    }

    /**
     * Records the instantiated entrypoints.
     *
     * @param entrypoints entrypoints in manifest declaration order; copied defensively so hook
     *     dispatch order cannot be mutated afterwards
     */
    public void setEntrypoints(final List<TurboismPlugin> entrypoints) {
        this.entrypoints = List.copyOf(entrypoints);
    }

    /**
     * Returns the context handed to this plugin's entrypoints.
     *
     * @return the plugin context, or null before composition
     */
    public PluginContext context() {
        return context;
    }

    /**
     * Records the composed plugin context.
     *
     * @param context the context handed to this plugin's entrypoints
     */
    public void setContext(final PluginContext context) {
        this.context = context;
    }

    /**
     * Returns why the plugin was disabled.
     *
     * @return the disabled reason, or null when the plugin was never disabled
     */
    public DisabledReason disabledReason() {
        return disabledReason;
    }

    /**
     * Disables the plugin and records why, so the reason is reportable rather than silent.
     *
     * @param reason the diagnostic reason for disabling
     */
    public void markDisabled(final DisabledReason reason) {
        this.disabledReason = reason;
        this.state = PluginLifecycleState.DISABLED;
    }
}
