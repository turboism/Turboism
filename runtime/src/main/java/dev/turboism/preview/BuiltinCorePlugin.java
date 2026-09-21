package dev.turboism.preview;

import java.net.URLClassLoader;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.event.GeneratedSubscriberCatalogLoader;
import dev.turboism.core.event.EventSubscriptionPermissionCatalog;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Runtime-owned built-in core admission; external discovery cannot construct this path.
 *
 * <p>The core runs on the same bounded lifecycle lane and under the same deadline as external
 * plugins so a stuck core cannot stall {@code loadAll} indefinitely, and its generation is fenced
 * and retained through the same mechanisms on timeout.</p>
 */
final class BuiltinCorePlugin {
    private static final String DESCRIPTOR = "META-INF/turboism/core-plugin.json";
    private static final String CORE_ID =
        dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID;

    private BuiltinCorePlugin() { }

    static LocalPluginRuntime.LoadedPlugin load(
        final PreviewPluginContextFactory contexts,
        final CorePluginServices services,
        final PreviewLog log,
        final PluginLifecycleLane lane,
        final PluginLifecyclePolicy policy,
        final RetainedPluginGenerations retention
    ) throws Exception {
        final CoreLoad state = new CoreLoad();
        final PluginLifecycleLease lease = new PluginLifecycleLease(CORE_ID);
        final PluginLifecycleLane.Invocation<LocalPluginRuntime.LoadedPlugin> invocation =
            lane.submit(CORE_ID, "load", () -> {
                try {
                    return loadOnLane(contexts, services, log, state, lease);
                } catch (Throwable failure) {
                    state.cleanupComplete = cleanupCore(state, log, policy, true);
                    throw failure;
                }
            });
        final PluginLifecycleLane.AwaitResult<LocalPluginRuntime.LoadedPlugin> result =
            lane.await(invocation, policy.loadTimeout(), lease);
        switch (result.outcome) {
            case SUCCEEDED -> {
                return result.value;
            }
            case FAILED -> {
                throw propagate(result.failure);
            }
            case REJECTED -> {
                fence(state, log);
                throw new IllegalStateException(
                    "Built-in core load rejected: lifecycle lane is saturated or closed"
                );
            }
            default -> {
                fence(state, log);
                if (!state.cleanupComplete) {
                    retention.retain(new RetainedPluginGenerations.RetainedGeneration(
                        CORE_ID,
                        invocation.workerDone,
                        state.context == null ? null : state.context.eventOwner(),
                        state.guard,
                        () -> cleanupCore(state, log, policy, false)
                    ));
                }
                throw new IllegalStateException(
                    "Built-in core load exceeded " + policy.loadTimeout(),
                    new TimeoutException("core lifecycle deadline expired")
                );
            }
        }
    }

    /** Immediate non-blocking fence for a timed-out core generation. */
    private static void fence(final CoreLoad state, final PreviewLog log) {
        if (state.guard != null) {
            state.guard.fence();
        }
        if (state.context != null) {
            try {
                state.context.eventOwner().beginClosing();
            } catch (Throwable failure) {
                log.error(CORE_ID, "Built-in core event fencing failed safely", failure);
            }
        }
        if (state.scope != null) {
            state.scope.seal();
        }
    }

    private static Exception propagate(final Throwable failure) {
        if (failure instanceof Exception exception) {
            return exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Built-in core load failed", failure);
    }

    private static LocalPluginRuntime.LoadedPlugin loadOnLane(
        final PreviewPluginContextFactory contexts,
        final CorePluginServices services,
        final PreviewLog log,
        final CoreLoad state,
        final PluginLifecycleLease lease
    ) throws Exception {
        final ClassLoader loader = MainToolbarPlugin.class.getClassLoader();
        state.resources = resourceLoader(loader);
        final PluginDescriptor descriptor;
        try (InputStream input = descriptorStream(loader)) {
            if (input == null) throw new IllegalStateException("built-in core descriptor is missing");
            descriptor = new PluginDescriptorParser().parse(input);
        }
        if (!CORE_ID.equals(descriptor.id())) {
            throw new IllegalStateException("built-in core descriptor identity mismatch");
        }
        log.info(
            descriptor.id(),
            "Plugin lifecycle: built-in load started version=" + descriptor.version()
        );
        final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
        runtime.transitionTo(PluginLifecycleState.RESOLVED);
        runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);
        state.guard = new PluginGenerationGuard(descriptor.id());
        state.scope = new DisposableScope();
        state.context = contexts.create(descriptor, state.resources, state.scope);
        state.plugin = CorePluginServices.instantiate(services, MainToolbarPlugin::new);
        runtime.setEntrypoints(List.of(state.plugin));
        final var eventSubscribers = new GeneratedSubscriberCatalogLoader().inspect(
            List.of(state.plugin),
            loader
        );
        if (!eventSubscribers.isEmpty()) {
            requireEventSubscribePermission(descriptor);
            EventSubscriptionPermissionCatalog.requireDeclared(
                descriptor,
                eventSubscribers
            );
        }
        state.context.eventOwner().registerAnnotated(eventSubscribers, List.of(state.plugin));
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);
        state.context.eventOwner().beginInitializing();
        lease.checkpoint();
        state.plugin.init(state.guard.wrap(state.context.context()));
        runtime.transitionTo(PluginLifecycleState.LOADED);
        log.info(descriptor.id(), "Plugin lifecycle: initialized entrypoints=1");
        state.context.eventOwner().beginEnabling();
        log.info(descriptor.id(), "Plugin lifecycle: enable started");
        lease.checkpoint();
        state.plugin.enable();
        state.enabled = true;
        runtime.transitionTo(PluginLifecycleState.ENABLED);
        final URL source = coreSource(loader);
        final Path artifact = Path.of(source.toURI()).toAbsolutePath().normalize();
        return lease.commit(() -> {
            state.context.eventOwner().activate();
            log.info(descriptor.id(), "Plugin lifecycle: enable succeeded entrypoints=1");
            log.info(
                descriptor.id(),
                "Plugin lifecycle: built-in load succeeded version=" + descriptor.version()
            );
            return new LocalPluginRuntime.LoadedPlugin(
                artifact, runtime, List.of(state.plugin), state.scope, state.resources,
                state.context.localization(), state.context.cleanupEvidence(),
                state.context.eventOwner(), state.context.context(), state.guard
            );
        });
    }

    /**
     * Idempotent teardown for a failed or fenced core generation. Returns {@code true} when every
     * step completed; {@code false} leaves the generation retained for a later re-drive.
     * Package-private so lifecycle tests can drive the ordering directly.
     */
    static boolean cleanupCore(
        final CoreLoad state,
        final PreviewLog log,
        final PluginLifecyclePolicy policy,
        final boolean awaitEvents
    ) {
        // Every failed core generation is fenced — ordinary failures too — so its guarded
        // context stops admitting work before rollback runs.
        fence(state, log);
        if (state.context != null && !state.eventOwnerClosed) {
            state.context.eventOwner().beginClosing();
            if (!state.context.eventOwner().awaitQuiescence(
                awaitEvents ? policy.eventQuiescenceTimeout() : Duration.ZERO
            )) {
                return false;
            }
            state.context.eventOwner().close();
            state.eventOwnerClosed = true;
        }
        // Admitted SDK calls must drain before teardown bodies: disable()/shutdown() can destroy
        // state an in-flight pre-fence call still touches — same ordering as the external loader
        // and the normal shutdown path.
        if (state.guard != null && !state.guard.drained()) {
            return false;
        }
        if (!state.pluginCleaned) {
            state.pluginCleaned = true;
            cleanupPlugin(state.plugin, state.enabled, log);
        }
        // One-shot disposal: DisposableScope.close() marks itself closed before running closers,
        // so a retry after a failure would be an empty success that erases the recorded failure
        // and releases the classloader early. Attempted and outcome are tracked separately; a
        // failed step keeps its outcome and the generation stays retained.
        if (!state.scopeAttempted) {
            state.scopeAttempted = true;
            state.scopeClosed = closeScope(state.scope, log);
        }
        if (state.scopeClosed && !state.resourcesAttempted) {
            state.resourcesAttempted = true;
            state.resourcesClosed = closeResources(state.resources, log);
        }
        if (!(state.scopeClosed && state.resourcesClosed)) {
            if (!state.retentionLogged) {
                state.retentionLogged = true;
                log.error(
                    CORE_ID,
                    "Built-in core classloader retained because cleanup did not quiesce",
                    new IllegalStateException("Built-in core cleanup is incomplete")
                );
            }
            return false;
        }
        return true;
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

    private static void cleanupPlugin(
        final TurboismPlugin plugin,
        final boolean enabled,
        final PreviewLog log
    ) {
        if (plugin == null) {
            return;
        }
        if (enabled) {
            try {
                plugin.disable();
            } catch (Throwable failure) {
                log.error(
                    CORE_ID,
                    "Built-in core enable rollback failed safely",
                    failure
                );
            }
        }
        try {
            plugin.shutdown();
        } catch (Throwable failure) {
            log.error(
                CORE_ID,
                "Built-in core shutdown rollback failed safely",
                failure
            );
        }
    }

    private static boolean closeScope(
        final DisposableScope scope,
        final PreviewLog log
    ) {
        if (scope == null) {
            return true;
        }
        try {
            scope.close();
            return true;
        } catch (Throwable failure) {
            log.error(
                CORE_ID,
                "Built-in core scope cleanup failed safely",
                failure
            );
            return false;
        }
    }

    private static boolean closeResources(
        final URLClassLoader resources,
        final PreviewLog log
    ) {
        if (resources == null) {
            return true;
        }
        try {
            resources.close();
            return true;
        } catch (Throwable failure) {
            log.error(
                CORE_ID,
                "Built-in core classloader cleanup failed safely",
                failure
            );
            return false;
        }
    }

    static URLClassLoader resourceLoader(final ClassLoader loader) {
        return new URLClassLoader(new URL[]{coreSource(loader)}, loader);
    }

    /**
     * The agent jar that carries the built-in core. With {@code Boot-Class-Path}
     * the core classes may be bootstrap-loaded, in which case the protection
     * domain has no CodeSource; fall back to the system classpath jar that
     * still carries the descriptor (the agent jar is appended to the system
     * classpath by {@code -javaagent}).
     */
    private static URL coreSource(final ClassLoader loader) {
        final java.security.CodeSource codeSource =
            MainToolbarPlugin.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) return codeSource.getLocation();
        final URL descriptorResource = loader != null
            ? loader.getResource(DESCRIPTOR)
            : ClassLoader.getSystemResource(DESCRIPTOR);
        if (descriptorResource == null) throw new IllegalStateException("built-in core descriptor is missing");
        if ("jar".equals(descriptorResource.getProtocol())) {
            try {
                return jarSourceUrl(descriptorResource);
            } catch (java.net.MalformedURLException impossible) {
                throw new IllegalStateException("built-in core source is invalid", impossible);
            }
        }
        return descriptorResource;
    }

    /**
     * {@code jar:file:/.../turboism-agent.jar!/entry} -> {@code file:/.../turboism-agent.jar}.
     */
    static URL jarSourceUrl(final URL descriptorResource) throws java.net.MalformedURLException {
        final String spec = descriptorResource.toExternalForm();
        final int separator = spec.indexOf("!/");
        if (separator <= 0) return descriptorResource;
        return java.net.URI.create(spec.substring(4, separator)).toURL();
    }

    private static InputStream descriptorStream(final ClassLoader loader) {
        final URL resource = loader != null
            ? loader.getResource(DESCRIPTOR)
            : ClassLoader.getSystemResource(DESCRIPTOR);
        if (resource == null) return null;
        try {
            return resource.openStream();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("built-in core descriptor is unreadable", failure);
        }
    }

    /** Mutable per-attempt state so a timeout fence and retained cleanup can reach the resources. */
    static final class CoreLoad {
        volatile URLClassLoader resources;
        volatile DisposableScope scope;
        volatile PluginContextBundle context;
        volatile PluginGenerationGuard guard;
        volatile TurboismPlugin plugin;
        volatile boolean enabled;
        volatile boolean cleanupComplete;
        volatile boolean eventOwnerClosed;
        volatile boolean pluginCleaned;
        volatile boolean scopeAttempted;
        volatile boolean scopeClosed;
        volatile boolean resourcesAttempted;
        volatile boolean resourcesClosed;
        volatile boolean retentionLogged;
    }
}
