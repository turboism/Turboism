package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.event.GeneratedSubscriberCatalogLoader;
import dev.turboism.core.event.EventSubscriberDescriptor;
import dev.turboism.core.event.EventSubscriptionPermissionCatalog;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.ContextClassLoaderScope;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Loads one plugin JAR and all of its ordered entrypoints atomically.
 *
 * <p>The whole construct/init/enable sequence runs as one task on the shared bounded
 * {@link PluginLifecycleLane}. The caller waits with {@link PluginLifecyclePolicy#loadTimeout()}:
 * on timeout the generation is fenced — admission closed through the guard, the event owner and a
 * sealed scope — and retained until the worker actually exits. A commit that already won the lease
 * race reports success; a late commit attempt hits the fence and unwinds through cleanup.</p>
 */
final class PreviewPluginLoader {

    private final PreviewPluginContextFactory contextFactory;
    private final PreviewLog log;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final PluginLifecycleLane lane;
    private final PluginLifecyclePolicy policy;
    private final RetainedPluginGenerations retention;

    PreviewPluginLoader(
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
        final PluginLifecycleLane lane,
        final PluginLifecyclePolicy policy,
        final RetainedPluginGenerations retention
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
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.retention = java.util.Objects.requireNonNull(retention, "retention");
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
        // The guard exists before the task starts so a timeout fence always has a gate to close,
        // even when the worker has not reached context creation yet.
        resources.guard = new PluginGenerationGuard(descriptor.id());
        final PluginLifecycleLease lease = new PluginLifecycleLease(descriptor.id());
        final PluginLifecycleLane.Invocation<LocalPluginRuntime.LoadedPlugin> invocation =
            lane.submit(descriptor.id(), "load", () -> {
                try {
                    return loadPlugin(candidate, runtime, resources, lease);
                } catch (Throwable failure) {
                    resources.cleanupComplete = cleanupFailed(resources, descriptor.id(), true);
                    throw failure;
                }
            });
        final PluginLifecycleLane.AwaitResult<LocalPluginRuntime.LoadedPlugin> result =
            lane.await(invocation, policy.loadTimeout(), lease);
        return switch (result.outcome) {
            case SUCCEEDED -> {
                loaded.add(result.value);
                log.info(
                    descriptor.id(),
                    "Plugin lifecycle: load succeeded name=" + descriptor.name()
                        + " version=" + descriptor.version()
                        + " entrypoints=" + resources.entrypoints.size()
                );
                yield PreviewPluginSummaryFactory.active(result.value);
            }
            case FAILED -> {
                recordFailure(candidate, runtime, resources.classLoader, failures, result.failure);
                retainIfIncomplete(resources, descriptor.id(), invocation);
                yield null;
            }
            case REJECTED -> {
                // The task never ran: nothing was constructed, so there is nothing to clean up.
                // Fence the pre-created guard anyway so no reference to it can admit work later.
                fenceFailedGeneration(resources);
                recordFailure(
                    candidate, runtime, resources.classLoader, failures,
                    new IllegalStateException(
                        "Plugin lifecycle lane is saturated or closed: " + descriptor.id()
                    )
                );
                yield null;
            }
            case TIMED_OUT -> {
                fenceFailedGeneration(resources);
                recordFailure(
                    candidate, runtime, resources.classLoader, failures,
                    new TimeoutException(
                        "Plugin load exceeded " + policy.loadTimeout() + ": " + descriptor.id()
                    )
                );
                retainIfIncomplete(resources, descriptor.id(), invocation);
                yield null;
            }
        };
    }

    /**
     * Immediately denies new work for a timed-out generation: SDK admission through the guarded
     * context, event subscription/delivery, and scope registrations. Non-blocking and idempotent;
     * safe while the worker is still inside plugin code.
     */
    private void fenceFailedGeneration(final LoadResources resources) {
        final PluginGenerationGuard guard = resources.guard;
        if (guard != null) {
            guard.fence();
        }
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner = resources.eventOwner;
        if (eventOwner != null) {
            try {
                eventOwner.beginClosing();
            } catch (Throwable failure) {
                log.error(
                    "plugin-loader",
                    "Plugin event owner fencing after load timeout failed safely",
                    failure
                );
            }
        }
        final DisposableScope scope = resources.scope;
        if (scope != null) {
            scope.seal();
        }
    }

    private void retainIfIncomplete(
        final LoadResources resources,
        final String pluginId,
        final PluginLifecycleLane.Invocation<?> invocation
    ) {
        if (resources.cleanupComplete) {
            return;
        }
        retention.retain(new RetainedPluginGenerations.RetainedGeneration(
            pluginId,
            invocation.workerDone,
            resources.eventOwner,
            resources.guard,
            () -> cleanupFailed(resources, pluginId, false)
        ));
        log.warn(
            pluginId,
            "Plugin lifecycle: failed generation retained until lifecycle work quiesces"
        );
    }

    private LocalPluginRuntime.LoadedPlugin loadPlugin(
        final PreviewPluginCandidate candidate,
        final PluginRuntime runtime,
        final LoadResources resources,
        final PluginLifecycleLease lease
    ) throws Exception {
        contextFactory.preflightEventContracts(candidate.descriptor());
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
        resources.eventSubscribers = new GeneratedSubscriberCatalogLoader().inspect(
            resources.entrypoints,
            resources.classLoader
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
            resources.eventSubscribers,
            resources.entrypoints
        );
        runtime.setContext(contextBundle.context());
        logLocalization(candidate.descriptor(), contextBundle);
        final dev.turboism.sdk.plugin.PluginContext guardedContext =
            resources.guard.wrap(contextBundle.context());
        resources.eventOwner.beginInitializing();

        for (TurboismPlugin entrypoint : resources.entrypoints) {
            lease.checkpoint();
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                resources.classLoader
            )) {
                entrypoint.init(guardedContext);
            }
            resources.initialized++;
        }
        runtime.transitionTo(PluginLifecycleState.LOADED);
        log.info(
            candidate.descriptor().id(),
            "Plugin lifecycle: initialized entrypoints=" + resources.initialized
        );

        resources.eventOwner.beginEnabling();
        enableAll(resources, runtime, candidate.descriptor().id(), lease);

        // Commit boundary: activation, hook registration and LoadedPlugin assembly run atomically
        // against the deadline fence. A late worker hits the fence here and unwinds instead of
        // activating a generation the caller already reported timed out.
        return lease.commit(() -> {
            resources.eventOwner.activate();
            registerHooks(candidate, resources, contextBundle);
            return new LocalPluginRuntime.LoadedPlugin(
                candidate.jar(),
                runtime,
                resources.entrypoints,
                resources.scope,
                resources.classLoader,
                contextBundle.localization(),
                contextBundle.cleanupEvidence(),
                contextBundle.eventOwner(),
                contextBundle.context(),
                resources.guard
            );
        });
    }

    private void registerHooks(
        final PreviewPluginCandidate candidate,
        final LoadResources resources,
        final PluginContextBundle contextBundle
    ) {
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
            resources.scope,
            contextFactory.eventBroker(),
            resources.eventOwner.key()
        );
        resources.projectLifecycleHooksRegistered = true;
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
        final String pluginId,
        final PluginLifecycleLease lease
    ) throws Exception {
        log.info(pluginId, "Plugin lifecycle: enable started");
        try {
            for (TurboismPlugin entrypoint : resources.entrypoints) {
                lease.checkpoint();
                try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                    resources.classLoader
                )) {
                    entrypoint.enable();
                }
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

    /**
     * Idempotent best-effort teardown of a failed load. Returns {@code true} only when every step
     * finished; a {@code false} result means the generation stays retained and this method will be
     * re-driven later — every step is guarded so re-entry is safe.
     *
     * @param awaitEvents when {@code true} (first pass inside the lifecycle task) the event owner
     *     gets a bounded quiescence wait; when {@code false} (retention re-drive) readiness is
     *     already gated and quiescence is only probed
     */
    private boolean cleanupFailed(
        final LoadResources resources,
        final String pluginId,
        final boolean awaitEvents
    ) {
        // Every failed generation is fenced — ordinary failures too, not only timeouts — so the
        // guarded context stops admitting work before rollback touches plugin code.
        fenceFailedGeneration(resources);
        if (resources.eventOwner != null && !resources.eventOwnerClosed) {
            resources.eventOwner.beginClosing();
            final boolean eventQuiesced = resources.eventOwner.awaitQuiescence(
                awaitEvents ? policy.eventQuiescenceTimeout() : Duration.ZERO
            );
            if (!eventQuiesced) {
                if (awaitEvents) {
                    log.error(
                        pluginId,
                        "Plugin event owner retained after load failure because callbacks did not quiesce",
                        new IllegalStateException("Plugin event callbacks are still active")
                    );
                }
                return false;
            }
            resources.eventOwner.close();
            resources.eventOwnerClosed = true;
        }
        unregisterHooks(resources);
        // Teardown bodies wait for SDK calls admitted before the fence to drain: disable() and
        // shutdown() can destroy state an in-flight pre-fence call still touches. The retention
        // watcher re-drives this method once they settle rather than racing plugin code.
        if (resources.guard != null && !resources.guard.drained()) {
            return false;
        }
        if (!resources.rolledBack) {
            resources.rolledBack = true;
            disableEnabledAfterFailure(resources, pluginId);
        }
        if (!resources.shutdownCalled) {
            resources.shutdownCalled = true;
            shutdownConstructedAfterFailure(resources, pluginId);
        }
        if (!resources.scopeClosed) {
            resources.scopeClosed = closeScopeAfterFailure(resources.scope, pluginId);
        }
        if (!resources.loaderClosed) {
            resources.loaderClosed = closeLoaderAfterFailure(
                resources.classLoader, resources.scopeClosed, pluginId
            );
        }
        return resources.scopeClosed && resources.loaderClosed;
    }

    private void unregisterHooks(final LoadResources resources) {
        if (resources.projectLifecycleHooksRegistered) {
            projectLifecycleHookRegistry.unregister(resources.eventOwner.key());
            resources.projectLifecycleHooksRegistered = false;
        }
        if (resources.editorObjectHooksRegistered) {
            editorObjectHookRegistry.unregister(resources.eventOwner.key());
            resources.editorObjectHooksRegistered = false;
        }
        if (resources.partHooksRegistered) {
            partHookRegistry.unregister(resources.eventOwner.key());
            resources.partHooksRegistered = false;
        }
        if (resources.parameterHooksRegistered) {
            parameterHookRegistry.unregister(resources.eventOwner.key());
            resources.parameterHooksRegistered = false;
        }
    }

    private void disableEnabledAfterFailure(
        final LoadResources resources,
        final String pluginId
    ) {
        for (int index = resources.enabled - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                resources.classLoader
            )) {
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
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                resources.classLoader
            )) {
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

    private boolean closeLoaderAfterFailure(
        final URLClassLoader classLoader,
        final boolean scopeClosed,
        final String pluginId
    ) {
        if (classLoader == null) {
            return true;
        }
        if (!scopeClosed) {
            log.error(
                pluginId,
                "Plugin classloader retained after load failure because cleanup did not quiesce",
                new IllegalStateException("Plugin scope cleanup is incomplete")
            );
            return false;
        }
        try {
            classLoader.close();
            return true;
        } catch (Throwable failure) {
            log.error(
                pluginId,
                "Plugin classloader cleanup after load failure failed safely",
                failure
            );
            return false;
        }
    }

    private static String safeMessage(final Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Per-attempt mutable load state. Fields the caller can touch while the lane worker is still
     * running (fencing, retention probes) are volatile; everything else is confined to the worker
     * and safely published through the task's completion.
     */
    private static final class LoadResources {
        volatile URLClassLoader classLoader;
        final List<TurboismPlugin> entrypoints = new ArrayList<>();
        List<EventSubscriberDescriptor> eventSubscribers = List.of();
        volatile DisposableScope scope;
        volatile dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner;
        volatile PluginGenerationGuard guard;
        volatile boolean cleanupComplete;
        volatile boolean eventOwnerClosed;
        volatile boolean scopeClosed;
        volatile boolean loaderClosed;
        volatile boolean rolledBack;
        volatile boolean shutdownCalled;
        List<dev.turboism.sdk.plugin.Registration> eventRegistrations = List.of();
        int initialized;
        int enabled;
        boolean parameterHooksRegistered;
        boolean partHooksRegistered;
        boolean editorObjectHooksRegistered;
        boolean projectLifecycleHooksRegistered;
    }
}
