package dev.turboism.plugin.core;

import dev.turboism.internal.core.CorePluginEntrypoint;
import dev.turboism.internal.core.CorePluginServices;
import dev.turboism.sdk.plugin.TurboismPlugin;

/**
 * Built-in core entrypoint supplied to the runtime by application composition. This is the
 * single supported construction path for {@link MainToolbarPlugin}; the runtime never names
 * the implementation class.
 */
public final class MainToolbarPluginEntrypoint implements CorePluginEntrypoint {

    @Override
    public TurboismPlugin createPlugin(final CorePluginServices services) {
        return new MainToolbarPlugin(services);
    }

    @Override
    public ClassLoader pluginClassLoader() {
        return MainToolbarPlugin.class.getClassLoader();
    }
}
