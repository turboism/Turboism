package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.hook.SemanticOperationHooks;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers ArtMesh, Deformer, and shared semantic hooks from ordered plugin entrypoints. */
public final class EditorObjectHookRegistry {
    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;
    public static final String INTERCEPT_PERMISSION = ParameterHookRegistry.INTERCEPT_PERMISSION;

    private final EditorObjectLifecycleCoordinator coordinator;
    private final ConcurrentHashMap<String, HookOwnership> ownerships = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    public EditorObjectHookRegistry(final EditorObjectLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        final Object token = new Object();
        synchronized (lifecycleLock) {
            if (ownerships.containsKey(descriptor.id())) {
                throw new IllegalStateException("Editor-object hooks already registered for " + descriptor.id());
            }
            unregisterHooks(descriptor.id());
            registerHooks(token, descriptor, entrypoints, logger);
        }
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final DisposableScope pluginScope = Objects.requireNonNull(scope, "scope");
        final HookOwnership ownership = new HookOwnership(plugin.id());
        synchronized (lifecycleLock) {
            if (ownerships.putIfAbsent(plugin.id(), ownership) != null) {
                throw new IllegalStateException("Editor-object hooks already registered for " + plugin.id());
            }
            try {
                pluginScope.register(ownership::close);
                registerHooks(ownership.token, plugin, entrypoints, logger);
                ownership.installed = true;
            } catch (RuntimeException | Error failure) {
                ownerships.remove(plugin.id(), ownership);
                unregisterHooks(plugin.id(), ownership.token);
                throw failure;
            }
        }
    }

    private void registerHooks(
        final Object token,
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final List<? extends TurboismPlugin> ordered = Objects.requireNonNull(entrypoints, "entrypoints");
        final PluginLogger pluginLogger = Objects.requireNonNull(logger, "logger");
        final boolean intercept = hasPermission(plugin, INTERCEPT_PERMISSION);
        final boolean observe = hasPermission(plugin, OBSERVE_PERMISSION);
        final List<DrawableHooks> drawableHooks = ordered.stream()
            .filter(DrawableHooks.class::isInstance).map(DrawableHooks.class::cast).toList();
        final List<DeformerHooks> deformerHooks = ordered.stream()
            .filter(DeformerHooks.class::isInstance).map(DeformerHooks.class::cast).toList();
        final List<SemanticOperationHooks> semanticHooks = ordered.stream()
            .filter(SemanticOperationHooks.class::isInstance)
            .map(SemanticOperationHooks.class::cast)
            .toList();
        if (!drawableHooks.isEmpty()) {
            coordinator.drawable().register(token, new DrawableLifecycleCoordinator.PluginHooks(
                plugin, drawableHooks, pluginLogger, intercept, observe
            ));
        }
        if (!deformerHooks.isEmpty()) {
            coordinator.deformer().register(token, new DeformerLifecycleCoordinator.PluginHooks(
                plugin, deformerHooks, pluginLogger, intercept, observe
            ));
        }
        if (!semanticHooks.isEmpty()) {
            coordinator.semantic().register(token, new SemanticOperationLifecycleCoordinator.PluginHooks(
                plugin, semanticHooks, pluginLogger, intercept, observe
            ));
        }
    }

    public void unregister(final String pluginId) {
        final String id = Objects.requireNonNull(pluginId, "pluginId");
        final HookOwnership ownership = ownerships.get(id);
        if (ownership != null) {
            ownership.close();
            return;
        }
        synchronized (lifecycleLock) {
            unregisterHooks(id);
        }
    }

    private void unregisterHooks(final String pluginId) {
        coordinator.drawable().unregister(pluginId);
        coordinator.deformer().unregister(pluginId);
        coordinator.semantic().unregister(pluginId);
    }

    private void unregisterHooks(final String pluginId, final Object token) {
        coordinator.drawable().unregister(pluginId, token);
        coordinator.deformer().unregister(pluginId, token);
        coordinator.semantic().unregister(pluginId, token);
    }

    private static boolean hasPermission(final PluginDescriptor descriptor, final String permissionId) {
        return descriptor.permissions().stream().anyMatch(permission -> permission.id().equals(permissionId));
    }

    private final class HookOwnership {
        private final String pluginId;
        private final Object token = new Object();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile boolean installed;

        private HookOwnership(final String pluginId) {
            this.pluginId = pluginId;
        }

        private void close() {
            if (closing.compareAndSet(false, true)) {
                try {
                    synchronized (lifecycleLock) {
                        if (installed) {
                            unregisterHooks(pluginId, token);
                        }
                        ownerships.remove(pluginId, this);
                    }
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }
            completion.join();
        }
    }
}
