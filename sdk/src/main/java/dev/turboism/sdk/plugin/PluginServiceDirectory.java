package dev.turboism.sdk.plugin;

import dev.turboism.sdk.Incubating;
import java.util.Set;

/**
 * Typed access to the optional services installed on a {@link PluginContext}.
 *
 * <p>This is the growth path for optional plugin-facing services: new optional services land
 * here and on {@link PluginService} instead of gaining a {@link PluginContext} accessor, so
 * the context's core contract stays small while the catalog of installable services can keep
 * expanding. Use {@link #get(Class)} for the service itself and {@link #installed()} (or
 * {@link PluginService#resolve(PluginContext)}) to probe before calling.</p>
 */
@Incubating
public interface PluginServiceDirectory {

    /**
     * Reports which optional services this context installed, under the same contract as
     * {@link PluginContext#availableServices()}: presence means the service resolves to a
     * usable object, not that the plugin holds its permissions or that version-routed
     * members succeed on the active host.
     *
     * @return the installed service members; empty when the context tracks no installations
     */
    Set<PluginService> installed();

    /**
     * Returns the service registered for {@code serviceType}, or {@code null} when the
     * context exposes only its unavailable sentinel (or no member names the type).
     *
     * @param <T> the service interface type
     * @param serviceType the service interface to resolve, e.g.
     *     {@code dev.turboism.sdk.storage.PluginStorage.class}
     * @return the installed service object, or {@code null} when absent
     */
    <T> T get(Class<T> serviceType);
}
