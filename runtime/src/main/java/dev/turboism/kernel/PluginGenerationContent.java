package dev.turboism.kernel;

import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.event.EventSubscriberDescriptor;
import dev.turboism.core.event.EventSubscriptionPermissionCatalog;
import dev.turboism.core.event.GeneratedSubscriberCatalogLoader;
import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.ContextClassLoaderScope;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The plugin kind's admission content: one JAR, its entrypoints, the four hook registries and the
 * event owner admitted for this generation.
 *
 * <p>Everything the plugin owns beyond the shared {@link AdmittedGeneration.GenerationState}
 * handles lives here, so {@code AdmittedGeneration} stays neutral between a plugin and the shell.
 * The teardown bodies run this generation's own rollback: disable in reverse enable order, then
 * shutdown, each isolated per entrypoint so one failure cannot skip the rest.</p>
 */
final class PluginGenerationContent implements AdmittedGeneration.Content<LocalPluginRuntime.LoadedPlugin> {

    private final PreviewPluginCandidate candidate;
    private final PluginRuntime runtime;
    private final KernelPluginContextFactory contextFactory;
    private final KernelLog log;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final List<TurboismPlugin> entrypoints = new ArrayList<>();
    private URLClassLoader classLoader;
    private PluginContextBundle contextBundle;
    private int initialized;
    private int enabled;
    private boolean parameterHooksRegistered;
    private boolean partHooksRegistered;
    private boolean editorObjectHooksRegistered;
    private boolean projectLifecycleHooksRegistered;

    PluginGenerationContent(
            final PreviewPluginCandidate candidate,
            final PluginRuntime runtime,
            final KernelPluginContextFactory contextFactory,
            final KernelLog log,
            final ParameterHookRegistry parameterHookRegistry,
            final PartHookRegistry partHookRegistry,
            final EditorObjectHookRegistry editorObjectHookRegistry,
            final ProjectLifecycleHookRegistry projectLifecycleHookRegistry) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.log = Objects.requireNonNull(log, "log");
        this.parameterHookRegistry = Objects.requireNonNull(parameterHookRegistry, "parameterHookRegistry");
        this.partHookRegistry = Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry = Objects.requireNonNull(editorObjectHookRegistry, "editorObjectHookRegistry");
        this.projectLifecycleHookRegistry =
                Objects.requireNonNull(projectLifecycleHookRegistry, "projectLifecycleHookRegistry");
    }

    @Override
    public String id() {
        return candidate.descriptor().id();
    }

    @Override
    public boolean claimsExecutors() {
        return true;
    }

    /**
     * The admission guard exists before the worker starts so a timeout fence always has a gate to
     * close, even when the worker has not reached context creation yet.
     */
    @Override
    public void bind(final AdmittedGeneration.GenerationState shared) {
        shared.guard = new PluginGenerationGuard(id());
    }

    @Override
    public LocalPluginRuntime.LoadedPlugin start(
            final AdmittedGeneration.GenerationState shared, final PluginLifecycleLease lease) throws Exception {
        final PluginDescriptor descriptor = candidate.descriptor();
        // Public event payload types resolve through the shared SDK classloader; the
        // plugin loader only isolates plugin classes behind the parent boundary.
        classLoader = new URLClassLoader(
                new URL[] {candidate.jar().toUri().toURL()},
                PluginClassLoader.resolvePluginParent(TurboismPlugin.class.getClassLoader()));
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);

        entrypoints.addAll(instantiateAll(descriptor, classLoader));
        runtime.setEntrypoints(entrypoints);
        final List<EventSubscriberDescriptor> eventSubscribers =
                new GeneratedSubscriberCatalogLoader().inspect(entrypoints, classLoader);
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);

        shared.scope = new DisposableScope();
        contextBundle = contextFactory.create(descriptor, classLoader, shared.scope, () -> !shared.guard.isFenced());
        shared.eventOwner = contextBundle.eventOwner();
        if (!eventSubscribers.isEmpty()) {
            requireEventSubscribePermission(descriptor);
            EventSubscriptionPermissionCatalog.requireDeclared(descriptor, eventSubscribers);
        }
        contextBundle.eventOwner().registerAnnotated(eventSubscribers, entrypoints);
        runtime.setContext(contextBundle.context());
        log.debug(
                descriptor.id(),
                "Localization active locale="
                        + contextBundle.localization().locale().toLanguageTag()
                        + " catalogs=" + descriptor.i18n().locales());
        final dev.turboism.sdk.plugin.PluginContext guardedContext = shared.guard.wrap(contextBundle.context());
        contextBundle.eventOwner().beginInitializing();

        for (TurboismPlugin entrypoint : entrypoints) {
            lease.checkpoint();
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(classLoader)) {
                entrypoint.init(guardedContext);
            }
            initialized++;
        }
        runtime.transitionTo(PluginLifecycleState.LOADED);
        log.info(descriptor.id(), "Plugin lifecycle: initialized entrypoints=" + initialized);

        contextBundle.eventOwner().beginEnabling();
        enableAll(lease);

        // Commit boundary: activation, hook registration and LoadedPlugin assembly run atomically
        // against the deadline fence. A late worker hits the fence here and unwinds instead of
        // activating a generation the caller already reported timed out.
        return lease.commit(() -> {
            contextBundle.eventOwner().activate();
            registerHooks(shared.scope);
            return new LocalPluginRuntime.LoadedPlugin(
                    candidate.jar(),
                    runtime,
                    entrypoints,
                    shared.scope,
                    classLoader,
                    contextBundle.localization(),
                    contextBundle.cleanupEvidence(),
                    contextBundle.eventOwner(),
                    contextBundle.context(),
                    shared.guard);
        });
    }

    @Override
    public void claimExecutors(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner = shared.eventOwner;
        if (eventOwner == null) {
            return;
        }
        // Capture the executor set under this generation's owner so the cleanup tail can release
        // exactly that set — a same-id reload cannot have its executor closed by this generation.
        try {
            eventOwner.claimExecutors();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id(), "Plugin executor claim after load failure failed safely", failure);
        }
    }

    @Override
    public void unregisterHooks(final AdmittedGeneration.GenerationState shared) {
        if (projectLifecycleHooksRegistered) {
            projectLifecycleHookRegistry.unregister(shared.eventOwner.key());
            projectLifecycleHooksRegistered = false;
        }
        if (editorObjectHooksRegistered) {
            editorObjectHookRegistry.unregister(shared.eventOwner.key());
            editorObjectHooksRegistered = false;
        }
        if (partHooksRegistered) {
            partHookRegistry.unregister(shared.eventOwner.key());
            partHooksRegistered = false;
        }
        if (parameterHooksRegistered) {
            parameterHookRegistry.unregister(shared.eventOwner.key());
            parameterHooksRegistered = false;
        }
    }

    /**
     * A failed load must not destroy plugin state while an in-flight backup write still touches it,
     * so the teardown quiesces backup work first — the same stage the close path has always run.
     */
    @Override
    public boolean quiesceBackup(final AdmittedGeneration.GenerationState shared) {
        if (contextBundle == null) {
            return true;
        }
        try {
            contextBundle.context().quiesceBackupOperations();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(
                    id(),
                    "Plugin lifecycle stage failed safely: PLUGIN_BACKUP_QUIESCENCE_FAILED",
                    new IllegalStateException("PLUGIN_BACKUP_QUIESCENCE_FAILED"));
            return false;
        }
    }

    /** Rollback of the failed attempt: disable what was enabled, then shut down what was built. */
    @Override
    public void closeBody(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        for (int index = enabled - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(classLoader)) {
                entrypoints.get(index).disable();
            } catch (Throwable exception) {
                FatalErrors.rethrowIfFatal(exception);
                log.error(id(), "Plugin enable rollback failed", exception);
            }
        }
        for (int index = entrypoints.size() - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(classLoader)) {
                entrypoints.get(index).shutdown();
            } catch (Throwable exception) {
                FatalErrors.rethrowIfFatal(exception);
                log.error(id(), "Plugin cleanup after load failure failed", exception);
            }
        }
    }

    @Override
    public AdmittedGeneration.StageOutcome closeScope(final AdmittedGeneration.GenerationState shared) {
        final DisposableScope scope = shared.scope;
        if (scope == null) {
            return AdmittedGeneration.StageOutcome.succeeded();
        }
        try {
            scope.close();
            return AdmittedGeneration.StageOutcome.succeeded();
        } catch (Exception exception) {
            log.error(id(), "Plugin scope cleanup after load failure failed", exception);
            return AdmittedGeneration.StageOutcome.failed();
        }
    }

    /**
     * The classloader is the last thing released, and only once the scope verifiably closed: a
     * scope closer may still resolve classes through it. An unproven scope keeps this
     * generation's loader open for its lifetime.
     */
    @Override
    public AdmittedGeneration.StageOutcome closeResources(final AdmittedGeneration.GenerationState shared) {
        if (!shared.scopeClosed) {
            return AdmittedGeneration.StageOutcome.failed();
        }
        if (classLoader == null) {
            return AdmittedGeneration.StageOutcome.succeeded();
        }
        try {
            classLoader.close();
            return AdmittedGeneration.StageOutcome.succeeded();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id(), "Plugin classloader cleanup after load failure failed safely", failure);
            return AdmittedGeneration.StageOutcome.failed();
        }
    }

    /** A failed load publishes its own verdict before teardown and owes no unload stage. */
    @Override
    public void finishDisposal(final AdmittedGeneration.GenerationState shared) {}

    @Override
    public void releaseExecutors(
            final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner = shared.eventOwner;
        if (eventOwner == null) {
            return;
        }
        try {
            eventOwner.releaseExecutors();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id(), "Plugin executor release after load failure failed safely", failure);
        }
    }

    @Override
    public String incompleteMessage() {
        return "Plugin classloader retained after load failure because cleanup did not quiesce";
    }

    @Override
    public String retainedMessage() {
        return "Plugin lifecycle: failed generation retained until lifecycle work quiesces";
    }

    URLClassLoader classLoader() {
        return classLoader;
    }

    int entrypointCount() {
        return entrypoints.size();
    }

    /** Event-owner generation this attempt admitted, or {@code NO_ADMITTED_GENERATION}. */
    long admittedGeneration() {
        return contextBundle == null
                ? dev.turboism.sdk.runtime.PluginLifecycleEvent.NO_ADMITTED_GENERATION
                : contextBundle.eventOwner().key().generation();
    }

    private void enableAll(final PluginLifecycleLease lease) throws Exception {
        log.info(id(), "Plugin lifecycle: enable started");
        try {
            for (TurboismPlugin entrypoint : entrypoints) {
                lease.checkpoint();
                try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(classLoader)) {
                    entrypoint.enable();
                }
                enabled++;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);
            log.info(id(), "Plugin lifecycle: enable succeeded entrypoints=" + enabled);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
            log.error(id(), "Plugin lifecycle: enable failed", failure);
            throw failure;
        }
    }

    private void registerHooks(final DisposableScope scope) {
        final PluginDescriptor descriptor = candidate.descriptor();
        final PluginLogger logger = contextBundle.context().logger();
        final PluginEventOwnerKey key = contextBundle.eventOwner().key();
        parameterHookRegistry.register(descriptor, entrypoints, logger, scope, contextFactory.eventBroker(), key);
        parameterHooksRegistered = true;
        partHookRegistry.register(descriptor, entrypoints, logger, scope, contextFactory.eventBroker(), key);
        partHooksRegistered = true;
        editorObjectHookRegistry.register(descriptor, entrypoints, logger, scope, contextFactory.eventBroker(), key);
        editorObjectHooksRegistered = true;
        projectLifecycleHookRegistry.register(
                descriptor, entrypoints, logger, scope, contextFactory.eventBroker(), key);
        projectLifecycleHooksRegistered = true;
    }

    private List<TurboismPlugin> instantiateAll(final PluginDescriptor descriptor, final URLClassLoader classLoader)
            throws Exception {
        final List<TurboismPlugin> instances = new ArrayList<>();
        for (String className : descriptor.entrypoints()) {
            final Class<?> type = Class.forName(className, true, classLoader);
            verifyEntrypoint(type, classLoader);
            instances.add((TurboismPlugin) type.getDeclaredConstructor().newInstance());
        }
        return List.copyOf(instances);
    }

    private static void verifyEntrypoint(final Class<?> type, final URLClassLoader classLoader)
            throws NoSuchMethodException {
        if (type.getClassLoader() != classLoader) {
            throw new IllegalArgumentException("Plugin entrypoint must be defined by its own plugin JAR");
        }
        if (!TurboismPlugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    "Plugin entrypoint does not implement TurboismPlugin: " + type.getName());
        }
        final Constructor<?> constructor = type.getDeclaredConstructor();
        if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isPublic(constructor.getModifiers())) {
            throw new IllegalArgumentException(
                    "Plugin entrypoint and no-arg constructor must be public: " + type.getName());
        }
    }

    private static void requireEventSubscribePermission(final PluginDescriptor descriptor) {
        final boolean allowed = descriptor.permissions().stream()
                .anyMatch(permission ->
                        dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(permission.id()));
        if (!allowed) {
            throw new IllegalArgumentException("@SubscribeEvent requires "
                    + dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE
                    + ": " + descriptor.id());
        }
    }
}
