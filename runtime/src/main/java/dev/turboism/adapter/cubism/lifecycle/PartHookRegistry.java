package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;

/** Discovers Part hook overrides from one plugin's ordered entrypoint instances. */
public final class PartHookRegistry {

    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;
    public static final String INTERCEPT_PERMISSION = ParameterHookRegistry.INTERCEPT_PERMISSION;

    private final PartLifecycleCoordinator coordinator;

    public PartHookRegistry(final PartLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        final List<PartHooks> hooks = Objects.requireNonNull(entrypoints, "entrypoints")
            .stream()
            .filter(PartHooks.class::isInstance)
            .map(PartHooks.class::cast)
            .toList();
        if (!hooks.isEmpty()) {
            final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
            coordinator.register(new PartLifecycleCoordinator.PluginHooks(
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
