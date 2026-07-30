package dev.turboism.preview;

import java.net.URLClassLoader;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.plugin.core.CorePluginServices;
import dev.turboism.plugin.core.MainToolbarPlugin;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/** Runtime-owned built-in core admission; external discovery cannot construct this path. */
final class BuiltinCorePlugin {
    private static final String DESCRIPTOR = "META-INF/turboism/core-plugin.json";

    private BuiltinCorePlugin() { }

    static LocalPluginRuntime.LoadedPlugin load(
        final PreviewPluginContextFactory contexts,
        final CorePluginServices services,
        final PreviewLog log
    ) throws Exception {
        final ClassLoader loader = MainToolbarPlugin.class.getClassLoader();
        final PluginDescriptor descriptor;
        try (InputStream input = loader.getResourceAsStream(DESCRIPTOR)) {
            if (input == null) throw new IllegalStateException("built-in core descriptor is missing");
            descriptor = new PluginDescriptorParser().parse(input);
        }
        if (!dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID.equals(descriptor.id())) {
            throw new IllegalStateException("built-in core descriptor identity mismatch");
        }
        final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
        runtime.transitionTo(PluginLifecycleState.RESOLVED);
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);
        final DisposableScope scope = new DisposableScope();
        final PluginContextBundle context = contexts.create(descriptor, loader, scope);
        final TurboismPlugin plugin = CorePluginServices.instantiate(services, MainToolbarPlugin::new);
        runtime.setEntrypoints(List.of(plugin));
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);
        plugin.init(context.context());
        runtime.transitionTo(PluginLifecycleState.LOADED);
        plugin.enable();
        runtime.transitionTo(PluginLifecycleState.ENABLED);
        final URL source = MainToolbarPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        final Path artifact = Path.of(source.toURI()).toAbsolutePath().normalize();
        log.info(descriptor.id(), "Loaded Runtime-owned built-in core " + descriptor.version());
        return new LocalPluginRuntime.LoadedPlugin(
            artifact, runtime, List.of(plugin), scope, new URLClassLoader(new URL[0], loader),
            context.localization(), context.cleanupEvidence()
        );
    }
}
