package dev.turboism.core.plugin.context;

import dev.turboism.sdk.plugin.DisposableScope;
import java.util.Objects;

/**
 * One one-shot, plugin-generation-owned service slot.
 *
 * <p>Directory-only registries such as mesh tools and modeling tools are installed by the runtime
 * after the context exists and must disappear when the plugin's disposable scope seals. Both halves
 * of that contract — install exactly once, release with the scope — live here so every installable
 * slot behaves identically instead of restating the bookkeeping per service.</p>
 *
 * @param <T> the installable service type
 */
final class CorePluginInstallableService<T extends AutoCloseable> {

    private final String label;
    private volatile T service;
    private boolean installed;

    /**
     * @param label short service name used in the duplicate-install diagnostic
     */
    CorePluginInstallableService(final String label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    /**
     * Installs the service and registers its release with the plugin scope.
     *
     * @param candidate the service to install; the scope owns it from here on
     * @param scope the plugin scope that releases the service on seal
     * @throws IllegalStateException if a service was already installed for this generation
     */
    synchronized void install(final T candidate, final DisposableScope scope) {
        Objects.requireNonNull(candidate, "service");
        if (installed) {
            throw new IllegalStateException(label + " already installed");
        }
        scope.register(() -> {
            synchronized (this) {
                service = null;
            }
            candidate.close();
        });
        service = candidate;
        installed = true;
    }

    /** @return the installed service; {@code null} before installation and once the scope sealed */
    T current() {
        return service;
    }
}
