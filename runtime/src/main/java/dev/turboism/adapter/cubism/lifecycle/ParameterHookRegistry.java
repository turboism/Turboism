package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.ParameterHooks;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;

/** Discovers parameter hook overrides from one plugin's ordered entrypoint instances. */
public final class ParameterHookRegistry {

    public static final String OBSERVE_PERMISSION = "turboism.cubism.model.observe";
    public static final String INTERCEPT_PERMISSION = "turboism.cubism.model.intercept";

    private final ParameterLifecycleCoordinator coordinator;
    private final Object lifecycleLock = new Object();

    public ParameterHookRegistry(final ParameterLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        register(descriptor, entrypoints, logger, null);
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        final List<ParameterHooks> hooks = Objects.requireNonNull(entrypoints, "entrypoints")
            .stream()
            .filter(ParameterHooks.class::isInstance)
            .map(ParameterHooks.class::cast)
            .toList();
        if (scope == null && hooks.isEmpty()) {
            return;
        }
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        synchronized (lifecycleLock) {
            if (scope != null) {
                coordinator.unregister(plugin.id());
            }
            if (hooks.isEmpty()) {
                return;
            }
            final ParameterLifecycleCoordinator.PluginHooks value = new ParameterLifecycleCoordinator.PluginHooks(
                plugin,
                hooks,
                Objects.requireNonNull(logger, "logger"),
                hasPermission(plugin, INTERCEPT_PERMISSION),
                hasPermission(plugin, OBSERVE_PERMISSION)
            );
            if (scope == null) {
                coordinator.register(value);
                return;
            }
            final Object token = new Object();
            try {
                coordinator.register(token, value);
                scope.register(() -> unregisterGeneration(plugin.id(), token));
            } catch (RuntimeException | Error failure) {
                coordinator.unregister(plugin.id(), token);
                throw failure;
            }
        }
    }

    public void unregister(final String pluginId) {
        synchronized (lifecycleLock) {
            coordinator.unregister(pluginId);
        }
    }

    private void unregisterGeneration(final String pluginId, final Object token) {
        synchronized (lifecycleLock) {
            coordinator.unregister(pluginId, token);
        }
    }

    private static boolean hasPermission(
        final PluginDescriptor descriptor,
        final String permissionId
    ) {
        return descriptor.permissions().stream()
            .anyMatch(permission -> permission.id().equals(permissionId));
    }
}
