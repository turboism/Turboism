package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/** Loads one plugin JAR and all of its ordered entrypoints atomically. */
final class PreviewPluginLoader {

    private final PreviewPluginContextFactory contextFactory;
    private final PreviewLog log;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;

    PreviewPluginLoader(
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry
    ) {
        this.contextFactory = contextFactory;
        this.log = log;
        this.loaded = loaded;
        this.parameterHookRegistry = java.util.Objects.requireNonNull(
            parameterHookRegistry,
            "parameterHookRegistry"
        );
        this.partHookRegistry = java.util.Objects.requireNonNull(partHookRegistry, "partHookRegistry");
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
            final LocalPluginRuntime.LoadedPlugin loadedPlugin = loadPlugin(
                candidate,
                runtime,
                resources
            );
            loaded.add(loadedPlugin);
            log.info(
                descriptor.id(),
                "Loaded plugin " + descriptor.name() + " " + descriptor.version()
                    + " entrypoints=" + resources.entrypoints.size()
            );
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
            new URL[]{candidate.jar().toUri().toURL()},
            TurboismPlugin.class.getClassLoader()
        );
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);

        resources.entrypoints.addAll(instantiateAll(
            candidate.descriptor(),
            resources.classLoader
        ));
        runtime.setEntrypoints(resources.entrypoints);
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);

        resources.scope = new DisposableScope();
        final PluginContextBundle contextBundle = contextFactory.create(
            candidate.descriptor(),
            resources.classLoader,
            resources.scope
        );
        runtime.setContext(contextBundle.context());
        logLocalization(candidate.descriptor(), contextBundle);

        for (TurboismPlugin entrypoint : resources.entrypoints) {
            entrypoint.init(contextBundle.context());
            resources.initialized++;
        }
        runtime.transitionTo(PluginLifecycleState.LOADED);

        enableAll(resources, runtime);
        parameterHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger()
        );
        resources.parameterHooksRegistered = true;
        partHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger()
        );
        resources.partHooksRegistered = true;
        return new LocalPluginRuntime.LoadedPlugin(
            candidate.jar(),
            runtime,
            resources.entrypoints,
            resources.scope,
            resources.classLoader,
            contextBundle.localization(),
            contextBundle.cleanupEvidence()
        );
    }

    private List<TurboismPlugin> instantiateAll(
        final PluginDescriptor descriptor,
        final URLClassLoader classLoader
    ) throws Exception {
        final List<TurboismPlugin> instances = new ArrayList<>();
        for (String className : descriptor.entrypoints()) {
            final Class<?> type = Class.forName(className, true, classLoader);
            verifyEntrypoint(type, classLoader);
            instances.add((TurboismPlugin) type.getDeclaredConstructor().newInstance());
        }
        return List.copyOf(instances);
    }

    private static void verifyEntrypoint(
        final Class<?> type,
        final URLClassLoader classLoader
    ) throws NoSuchMethodException {
        if (type.getClassLoader() != classLoader) {
            throw new IllegalArgumentException(
                "Plugin entrypoint must be defined by its own plugin JAR"
            );
        }
        if (!TurboismPlugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                "Plugin entrypoint does not implement TurboismPlugin: " + type.getName()
            );
        }
        final Constructor<?> constructor = type.getDeclaredConstructor();
        if (!Modifier.isPublic(type.getModifiers())
            || !Modifier.isPublic(constructor.getModifiers())) {
            throw new IllegalArgumentException(
                "Plugin entrypoint and no-arg constructor must be public: " + type.getName()
            );
        }
    }

    private void logLocalization(
        final PluginDescriptor descriptor,
        final PluginContextBundle contextBundle
    ) {
        log.debug(
            descriptor.id(),
            "Localization active locale="
                + contextBundle.localization().locale().toLanguageTag()
                + " catalogs=" + descriptor.i18n().locales()
        );
    }

    private static void enableAll(
        final LoadResources resources,
        final PluginRuntime runtime
    ) throws Exception {
        try {
            for (TurboismPlugin entrypoint : resources.entrypoints) {
                entrypoint.enable();
                resources.enabled++;
            }
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
                ? PluginLifecycleState.CLASSLOADER_FAILED
                : PluginLifecycleState.LOAD_FAILED);
        }
        failures.add(new LocalPluginRuntime.PluginFailure(
            candidate.descriptor().id(),
            candidate.jar(),
            runtime.state().name(),
            safeMessage(failure)
        ));
        log.error(candidate.descriptor().id(), "Plugin load failed", failure);
    }

    private void cleanupFailed(
        final LoadResources resources,
        final String pluginId
    ) {
        if (resources.partHooksRegistered) {
            partHookRegistry.unregister(pluginId);
            resources.partHooksRegistered = false;
        }
        if (resources.parameterHooksRegistered) {
            parameterHookRegistry.unregister(pluginId);
            resources.parameterHooksRegistered = false;
        }
        disableEnabledAfterFailure(resources, pluginId);
        shutdownConstructedAfterFailure(resources, pluginId);
        final boolean scopeClosed = closeScopeAfterFailure(resources.scope, pluginId);
        closeLoaderAfterFailure(resources.classLoader, scopeClosed, pluginId);
    }

    private void disableEnabledAfterFailure(
        final LoadResources resources,
        final String pluginId
    ) {
        for (int index = resources.enabled - 1; index >= 0; index--) {
            try {
                resources.entrypoints.get(index).disable();
            } catch (Exception exception) {
                log.error(pluginId, "Plugin enable rollback failed", exception);
            }
        }
    }

    private void shutdownConstructedAfterFailure(
        final LoadResources resources,
        final String pluginId
    ) {
        for (int index = resources.entrypoints.size() - 1; index >= 0; index--) {
            try {
                resources.entrypoints.get(index).shutdown();
            } catch (Exception exception) {
                log.error(pluginId, "Plugin cleanup after load failure failed", exception);
            }
        }
    }

    private boolean closeScopeAfterFailure(
        final DisposableScope scope,
        final String pluginId
    ) {
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
            log.error(
                pluginId,
                "Plugin classloader retained after load failure because cleanup did not quiesce",
                new IllegalStateException("Plugin scope cleanup is incomplete")
            );
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
        return message == null || message.isBlank()
            ? failure.getClass().getName()
            : message;
    }

    private static final class LoadResources {
        private URLClassLoader classLoader;
        private DisposableScope scope;
        private final List<TurboismPlugin> entrypoints = new ArrayList<>();
        private int initialized;
        private int enabled;
        private boolean parameterHooksRegistered;
        private boolean partHooksRegistered;
    }
}
