package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.ParameterHooks;
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

    public ParameterHookRegistry(final ParameterLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        final List<ParameterHooks> hooks = Objects.requireNonNull(entrypoints, "entrypoints")
            .stream()
            .filter(ParameterHooks.class::isInstance)
            .map(ParameterHooks.class::cast)
            .toList();
        if (!hooks.isEmpty()) {
            final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
            coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
                plugin,
                hooks,
                Objects.requireNonNull(logger, "logger"),
                hasPermission(plugin, INTERCEPT_PERMISSION),
                hasPermission(plugin, OBSERVE_PERMISSION)
            ));
        }
    }

    public void unregister(final String pluginId) {
        coordinator.unregister(pluginId);
    }

    private static boolean hasPermission(
        final PluginDescriptor descriptor,
        final String permissionId
    ) {
        return descriptor.permissions().stream()
            .anyMatch(permission -> permission.id().equals(permissionId));
    }
}
