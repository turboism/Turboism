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

    /**
     * Registers a plugin's parameter hooks for the lifetime of the host session, with no scope-bound
     * detachment. A plugin whose entrypoints implement no parameter hooks installs nothing.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @throws NullPointerException when {@code entrypoints} is null, or when {@code descriptor} or
     *     {@code logger} is null and parameter hooks were found
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        register(descriptor, entrypoints, logger, null);
    }

    /**
     * Registers a plugin's parameter hooks, deriving intercept and observe capability from the
     * descriptor's declared permissions. When {@code scope} is non-null any earlier registration for
     * this plugin id is dropped first and the new generation is detached when the scope is disposed;
     * a failure to arm the scope rolls the registration back. When {@code scope} is null the
     * registration lasts for the session.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @param scope plugin scope whose disposal unregisters this generation, or null for session scope
     * @throws NullPointerException when {@code entrypoints} is null, or when {@code descriptor} or
     *     {@code logger} is null and parameter hooks were found
     */
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

    /**
     * Detaches every parameter hook registration held under the given plugin id, regardless of
     * generation. Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     */
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
