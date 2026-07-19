package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/** Loads one resolved plugin and cleans partially-created resources on failure. */
final class PreviewPluginLoader {

    private final PreviewPluginContextFactory contextFactory;
    private final PreviewLog log;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;

    PreviewPluginLoader(
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded
    ) {
        this.contextFactory = contextFactory;
        this.log = log;
        this.loaded = loaded;
    }

    LocalPluginRuntime.LoadedPluginSummary load(
        final PreviewPluginCandidate candidate,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        final PluginDescriptor descriptor = candidate.descriptor();
        final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
        runtime.transitionTo(PluginLifecycleState.RESOLVED);
        final LoadResources resources = new LoadResources();
        try {
            final LocalPluginRuntime.LoadedPlugin loadedPlugin = loadPlugin(candidate, runtime, resources);
            loaded.add(loadedPlugin);
            log.info(descriptor.id(), "Loaded plugin " + descriptor.name() + " " + descriptor.version());
            return PreviewPluginSummaryFactory.active(loadedPlugin);
        } catch (Throwable failure) {
            recordFailure(candidate, runtime, resources.classLoader, failures, failure);
            cleanupFailed(resources, descriptor.id());
            return null;
        }
    }

    private LocalPluginRuntime.LoadedPlugin loadPlugin(
        final PreviewPluginCandidate candidate,
        final PluginRuntime runtime,
        final LoadResources resources
    ) throws Exception {
        resources.classLoader = new URLClassLoader(
            new URL[]{candidate.jar().toUri().toURL()}, TurboismPlugin.class.getClassLoader()
        );
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);
        resources.plugin = instantiate(candidate.descriptor(), resources.classLoader);
        runtime.setInstance(resources.plugin);
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);
        resources.scope = new DisposableScope();
        final PluginContextBundle contextBundle = contextFactory.create(
            candidate.descriptor(), resources.classLoader, resources.scope
        );
        runtime.setContext(contextBundle.context());
        logLocalization(candidate.descriptor(), contextBundle);
        resources.plugin.init(contextBundle.context());
        runtime.transitionTo(PluginLifecycleState.LOADED);
        enable(resources.plugin, runtime);
        return new LocalPluginRuntime.LoadedPlugin(
            candidate.jar(), runtime, resources.plugin, resources.scope, resources.classLoader,
            contextBundle.localization(), contextBundle.cleanupEvidence()
        );
    }

    private TurboismPlugin instantiate(
        final PluginDescriptor descriptor,
        final URLClassLoader classLoader
    ) throws Exception {
        final Class<?> type = Class.forName(descriptor.entrypoints().get("plugin"), true, classLoader);
        verifyEntrypoint(type, classLoader);
        return (TurboismPlugin) type.getDeclaredConstructor().newInstance();
    }

    private static void verifyEntrypoint(final Class<?> type, final URLClassLoader classLoader)
        throws NoSuchMethodException {
        if (type.getClassLoader() != classLoader) {
            throw new IllegalArgumentException("Plugin entrypoint must be defined by its own plugin JAR");
        }
        if (!TurboismPlugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Plugin entrypoint does not implement TurboismPlugin");
        }
        final Constructor<?> constructor = type.getDeclaredConstructor();
        if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isPublic(constructor.getModifiers())) {
            throw new IllegalArgumentException("Plugin entrypoint and no-arg constructor must be public");
        }
    }

    private void logLocalization(
        final PluginDescriptor descriptor,
        final PluginContextBundle contextBundle
    ) {
        log.debug(descriptor.id(), "Localization active locale="
            + contextBundle.localization().locale().toLanguageTag());
    }

    private static void enable(final TurboismPlugin plugin, final PluginRuntime runtime) throws Exception {
        try {
            plugin.enable();
            runtime.transitionTo(PluginLifecycleState.ENABLED);
        } catch (Exception failure) {
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
            throw failure;
        }
    }

    private void recordFailure(
        final PreviewPluginCandidate candidate,
        final PluginRuntime runtime,
        final URLClassLoader classLoader,
        final List<LocalPluginRuntime.PluginFailure> failures,
        final Throwable failure
    ) {
        if (runtime.state() != PluginLifecycleState.ENABLE_FAILED) {
            runtime.transitionTo(classLoader == null
                ? PluginLifecycleState.CLASSLOADER_FAILED : PluginLifecycleState.LOAD_FAILED);
        }
        failures.add(new LocalPluginRuntime.PluginFailure(
            candidate.descriptor().id(), candidate.jar(), runtime.state().name(), safeMessage(failure)
        ));
        log.error(candidate.descriptor().id(), "Plugin load failed", failure);
    }

    private void cleanupFailed(final LoadResources resources, final String pluginId) {
        shutdownAfterFailure(resources.plugin, pluginId);
        final boolean scopeClosed = closeScopeAfterFailure(resources.scope, pluginId);
        closeLoaderAfterFailure(resources.classLoader, scopeClosed, pluginId);
    }

    private void shutdownAfterFailure(final TurboismPlugin plugin, final String pluginId) {
        if (plugin == null) {
            return;
        }
        try {
            plugin.shutdown();
        } catch (Exception exception) {
            log.error(pluginId, "Plugin cleanup after load failure failed", exception);
        }
    }

    private boolean closeScopeAfterFailure(final DisposableScope scope, final String pluginId) {
        if (scope == null) {
            return true;
        }
        try {
            scope.close();
            return true;
        } catch (Exception exception) {
            log.error(pluginId, "Plugin scope cleanup after load failure failed", exception);
            return false;
        }
    }

    private void closeLoaderAfterFailure(
        final URLClassLoader classLoader,
        final boolean scopeClosed,
        final String pluginId
    ) {
        if (classLoader == null) {
            return;
        }
        if (!scopeClosed) {
            log.error(pluginId, "Plugin classloader retained after load failure because cleanup did not quiesce",
                new IllegalStateException("Plugin scope cleanup is incomplete"));
            return;
        }
        try {
            classLoader.close();
        } catch (Exception exception) {
            log.error(pluginId, "Plugin classloader cleanup after load failure failed", exception);
        }
    }

    private static String safeMessage(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private static final class LoadResources {
        private URLClassLoader classLoader;
        private DisposableScope scope;
        private TurboismPlugin plugin;
    }
}
