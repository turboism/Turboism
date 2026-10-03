package dev.turboism.sdk.plugin;

import java.util.Optional;
import java.util.Set;

/**
 * Typed access to the optional services installed on a {@link PluginContext}.
 *
 * <p>This is the single service-discovery surface: every optional plugin-facing service
 * resolves here through {@link #find(Class)} or {@link #require(Class)}, and new optional
 * services land here and on {@link PluginService} instead of gaining a {@link PluginContext}
 * accessor. {@link #installed()} probes which {@link PluginService} members resolve before
 * calling.</p>
 */
public interface PluginServiceDirectory {

    /**
     * Reports which optional services this context installed: presence means the service
     * resolves to a usable object, not that the plugin holds its permissions or that
     * version-routed members succeed on the active host.
     *
     * @return the installed service members; empty when the context tracks no installations
     */
    Set<PluginService> installed();

    /**
     * Returns the service registered for {@code serviceType}, or empty when the
     * context exposes only its unavailable sentinel (or no member names the type).
     *
     * @param <T> the service interface type
     * @param serviceType the service interface to resolve, e.g.
     *     {@code dev.turboism.sdk.storage.PluginStorage.class}
     * @return the installed service object; empty when absent, never {@code null}
     */
    <T> Optional<T> find(Class<T> serviceType);

    /**
     * Returns the service registered for {@code serviceType}, throwing a structured
     * {@link PluginServiceUnavailableException} when it is absent. Use this where the
     * service is required — it preserves the typed-failure behavior the pre-directory
     * unavailable sentinels provided, instead of surfacing a {@link NullPointerException}
     * at the first dereference.
     *
     * @param <T> the service interface type
     * @param serviceType the service interface to resolve
     * @return the installed service object, never {@code null}
     * @throws PluginServiceUnavailableException when the service is absent
     */
    default <T> T require(final Class<T> serviceType) {
        return find(serviceType)
                .orElseThrow(() -> new PluginServiceUnavailableException(
                        serviceType, PluginService.forType(serviceType).orElse(null)));
    }
}
