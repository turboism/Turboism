package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.EventSubscriberDescriptor;
import dev.turboism.core.event.EventSubscriptionPermissionCatalog;
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
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final java.util.Set<LoadResources> retainedFailures =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    PreviewPluginLoader(
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry
    ) {
        this.contextFactory = contextFactory;
        this.log = log;
        this.loaded = loaded;
        this.parameterHookRegistry = java.util.Objects.requireNonNull(
            parameterHookRegistry,
            "parameterHookRegistry"
        );
        this.partHookRegistry = java.util.Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry = java.util.Objects.requireNonNull(
            editorObjectHookRegistry,
            "editorObjectHookRegistry"
        );
        this.projectLifecycleHookRegistry = java.util.Objects.requireNonNull(
            projectLifecycleHookRegistry,
            "projectLifecycleHookRegistry"
        );
    }

    LocalPluginRuntime.LoadedPluginSummary load(
        final PreviewPluginCandidate candidate,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        final PluginDescriptor descriptor = candidate.descriptor();
        log.info(
            descriptor.id(),
            "Plugin lifecycle: load started name=" + descriptor.name()
                + " version=" + descriptor.version()
        );
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
                "Plugin lifecycle: load succeeded name=" + descriptor.name()
                    + " version=" + descriptor.version()
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
            resolvePluginParent(TurboismPlugin.class.getClassLoader())
        );
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);

        resources.entrypoints.addAll(instantiateAll(
            candidate.descriptor(),
            resources.classLoader
        ));
        runtime.setEntrypoints(resources.entrypoints);
        resources.eventSubscribers = new EntrypointSubscriberCatalog().inspect(
            resources.entrypoints
        );
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);

        resources.scope = new DisposableScope();
        final PluginContextBundle contextBundle = contextFactory.create(
            candidate.descriptor(),
            resources.classLoader,
            resources.scope
        );
        resources.eventOwner = contextBundle.eventOwner();
        if (!resources.eventSubscribers.isEmpty()) {
            requireEventSubscribePermission(candidate.descriptor());
            EventSubscriptionPermissionCatalog.requireDeclared(
                candidate.descriptor(),
                resources.eventSubscribers
            );
        }
        resources.eventRegistrations = contextBundle.eventOwner().registerAnnotated(
            resources.eventSubscribers
        );
        runtime.setContext(contextBundle.context());
        logLocalization(candidate.descriptor(), contextBundle);
        resources.eventOwner.beginInitializing();

        for (TurboismPlugin entrypoint : resources.entrypoints) {
            entrypoint.init(contextBundle.context());
            resources.initialized++;
        }
        runtime.transitionTo(PluginLifecycleState.LOADED);
        log.info(
            candidate.descriptor().id(),
            "Plugin lifecycle: initialized entrypoints=" + resources.initialized
        );

        resources.eventOwner.beginEnabling();
        enableAll(resources, runtime, candidate.descriptor().id());
        resources.eventOwner.activate();
        parameterHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger(),
            resources.scope,
            contextFactory.eventBroker(),
            resources.eventOwner.key()
        );
        resources.parameterHooksRegistered = true;
        partHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger(),
            resources.scope,
            contextFactory.eventBroker(),
            resources.eventOwner.key()
        );
        resources.partHooksRegistered = true;
        editorObjectHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger(),
            resources.scope,
            contextFactory.eventBroker(),
            resources.eventOwner.key()
        );
        resources.editorObjectHooksRegistered = true;
        projectLifecycleHookRegistry.register(
            candidate.descriptor(),
            resources.entrypoints,
            contextBundle.context().logger(),
            resources.scope
        );
        resources.projectLifecycleHooksRegistered = true;
        return new LocalPluginRuntime.LoadedPlugin(
            candidate.jar(),
            runtime,
            resources.entrypoints,
            resources.scope,
            resources.classLoader,
            contextBundle.localization(),
            contextBundle.cleanupEvidence(),
            contextBundle.eventOwner()
        );
    }

    /**
     * Package-private parent-selection seam. When the SDK is bootstrap-loaded
     * by the agent Boot-Class-Path, {@code TurboismPlugin.class.getClassLoader()}
     * is null and the platform loader is required so plugin JARs stay visible to
     * JDK platform modules (for example {@code jdk.httpserver}).
     */
    static ClassLoader resolvePluginParent(final ClassLoader sdkClassLoader) {
        return sdkClassLoader != null
            ? sdkClassLoader
            : ClassLoader.getPlatformClassLoader();
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

    private static void requireEventSubscribePermission(final PluginDescriptor descriptor) {
        final boolean allowed = descriptor.permissions().stream().anyMatch(permission ->
            dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(
                permission.id()
            )
        );
        if (!allowed) {
            throw new IllegalArgumentException(
                "@SubscribeEvent requires "
                    + dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE
                    + ": " + descriptor.id()
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

    private void enableAll(
        final LoadResources resources,
        final PluginRuntime runtime,
        final String pluginId
    ) throws Exception {
        log.info(pluginId, "Plugin lifecycle: enable started");
        try {
            for (TurboismPlugin entrypoint : resources.entrypoints) {
                entrypoint.enable();
                resources.enabled++;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);
            log.info(
                pluginId,
                "Plugin lifecycle: enable succeeded entrypoints=" + resources.enabled
            );
        } catch (Exception failure) {
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
            log.error(pluginId, "Plugin lifecycle: enable failed", failure);
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
        log.error(
            candidate.descriptor().id(),
            "Plugin lifecycle: load failed state=" + runtime.state(),
            failure
        );
    }

    private void cleanupFailed(
        final LoadResources resources,
        final String pluginId
    ) {
        final boolean eventQuiesced = closeEventOwnerAfterFailure(resources.eventOwner, pluginId);
        if (!eventQuiesced) {
            retainedFailures.add(resources);
            scheduleRetainedFailureCleanup(resources, pluginId);
            return;
        }
        if (resources.projectLifecycleHooksRegistered) {
            projectLifecycleHookRegistry.unregister(pluginId);
            resources.projectLifecycleHooksRegistered = false;
        }
        if (resources.editorObjectHooksRegistered) {
            editorObjectHookRegistry.unregister(pluginId);
            resources.editorObjectHooksRegistered = false;
        }
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
        closeLoaderAfterFailure(resources.classLoader, scopeClosed, eventQuiesced, pluginId);
    }

    private void scheduleRetainedFailureCleanup(
        final LoadResources resources,
        final String pluginId
    ) {
        final Thread reaper = new Thread(
            () -> reapRetainedFailure(resources, pluginId),
            "turboism-event-load-zombie-" + pluginId
        );
        reaper.setDaemon(true);
        reaper.setContextClassLoader(PreviewPluginLoader.class.getClassLoader());
        reaper.start();
    }

    private void reapRetainedFailure(
        final LoadResources resources,
        final String pluginId
    ) {
        try {
            if (!resources.eventOwner.awaitQuiescence(java.time.Duration.ofDays(3650))) {
                return;
            }
            resources.eventOwner.close();
            cleanupFailedAfterEventQuiescence(resources, pluginId);
            retainedFailures.remove(resources);
            log.info(pluginId, "Retained failed plugin generation cleanup succeeded");
        } catch (Throwable failure) {
            log.error(pluginId, "Retained failed plugin generation cleanup failed safely", failure);
        }
    }

    private void cleanupFailedAfterEventQuiescence(
        final LoadResources resources,
        final String pluginId
    ) {
        if (resources.projectLifecycleHooksRegistered) {
            projectLifecycleHookRegistry.unregister(pluginId);
            resources.projectLifecycleHooksRegistered = false;
        }
        if (resources.editorObjectHooksRegistered) {
            editorObjectHookRegistry.unregister(pluginId);
            resources.editorObjectHooksRegistered = false;
        }
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
        closeLoaderAfterFailure(resources.classLoader, scopeClosed, true, pluginId);
    }

    private boolean closeEventOwnerAfterFailure(
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner,
        final String pluginId
    ) {
        if (eventOwner == null) {
            return true;
        }
        eventOwner.beginClosing();
        if (!eventOwner.awaitQuiescence(java.time.Duration.ofSeconds(5))) {
            log.error(
                pluginId,
                "Plugin event owner retained after load failure because callbacks did not quiesce",
                new IllegalStateException("Plugin event callbacks are still active")
            );
            return false;
        }
        eventOwner.close();
        return true;
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
        final boolean eventQuiesced,
        final String pluginId
    ) {
        if (classLoader == null) {
            return;
        }
        if (!scopeClosed || !eventQuiesced) {
            log.error(
                pluginId,
                "Plugin classloader retained after load failure because cleanup did not quiesce",
                new IllegalStateException(
                    !scopeClosed
                        ? "Plugin scope cleanup is incomplete"
                        : "Plugin event callbacks are still active"
                )
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
        private dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner;
        private List<EventSubscriberDescriptor> eventSubscribers = List.of();
        private List<dev.turboism.sdk.plugin.Registration> eventRegistrations = List.of();
        private final List<TurboismPlugin> entrypoints = new ArrayList<>();
        private int initialized;
        private int enabled;
        private boolean parameterHooksRegistered;
        private boolean partHooksRegistered;
        private boolean editorObjectHooksRegistered;
        private boolean projectLifecycleHooksRegistered;
    }
}
