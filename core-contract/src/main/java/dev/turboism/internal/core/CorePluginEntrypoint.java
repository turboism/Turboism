package dev.turboism.internal.core;

import dev.turboism.sdk.plugin.TurboismPlugin;


/**
 * Built-in core UI entrypoint supplied by application composition.
 *
 * <p>The runtime owns admission, lifecycle and the service handoff; it never names the core UI
 * implementation class directly. Composition (the bootstrap agent) injects the single
 * implementation assembled from {@code plugins:core}, and a runtime started without one simply
 * runs headless — external plugins still load.</p>
 */
public interface CorePluginEntrypoint {

    /**
     * Constructs the built-in core plugin instance with the runtime-owned service handoff.
     *
     * @param services runtime-owned services; must not be {@code null}
     * @return the constructed plugin; must not be {@code null}
     */
    TurboismPlugin createPlugin(CorePluginServices services);

    /**
     * @return the class loader that carries the core implementation classes and resources
     *     (descriptor, i18n, icons); used for descriptor parsing and subscriber inspection
     */
    ClassLoader pluginClassLoader();
}
