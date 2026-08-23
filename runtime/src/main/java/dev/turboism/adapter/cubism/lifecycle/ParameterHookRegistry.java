package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.cubism.hook.ParameterHooks;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapts legacy parameter hook overrides onto the unified Runtime event broker. */
public final class ParameterHookRegistry {

    public static final String OBSERVE_PERMISSION = "turboism.cubism.model.observe";
    public static final String INTERCEPT_PERMISSION = "turboism.cubism.model.intercept";

    private final ParameterLifecycleCoordinator coordinator;
    private final Object lifecycleLock = new Object();
    private final java.util.Map<PluginEventOwnerKey, AdapterRegistration> registrations =
        new java.util.HashMap<>();

    public ParameterHookRegistry(final ParameterLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public ParameterLifecycleCoordinator coordinator() {
        return coordinator;
    }

    /** Session-scoped compatibility registration retained for focused adapter tests. */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        registerCompatibility(descriptor, entrypoints, logger, null);
    }

    /** Scope-bound compatibility registration retained outside Preview composition. */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        registerCompatibility(descriptor, entrypoints, logger, scope);
    }

    private void registerCompatibility(
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
            final ParameterLifecycleCoordinator.PluginHooks value =
                new ParameterLifecycleCoordinator.PluginHooks(
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
                scope.register(() -> coordinator.unregister(plugin.id(), token));
            } catch (RuntimeException | Error failure) {
                coordinator.unregister(plugin.id(), token);
                throw failure;
            }
        }
    }

    /**
     * Registers legacy overrides as broker subscribers owned by the exact plugin generation.
     * Entrypoints that also declare annotated handlers for a state are not adapted for that state.
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope,
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner
    ) {
        final List<? extends TurboismPlugin> instances = List.copyOf(
            Objects.requireNonNull(entrypoints, "entrypoints")
        );
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final PluginLogger sink = Objects.requireNonNull(logger, "logger");
        final RuntimeEventBroker runtimeBroker = Objects.requireNonNull(broker, "broker");
        final PluginEventOwnerKey eventOwner = Objects.requireNonNull(owner, "owner");
        final List<Registration> installed = new ArrayList<>();
        int entrypointOrdinal = 0;
        for (TurboismPlugin entrypoint : instances) {
            if (!(entrypoint instanceof ParameterHooks hooks)) {
                entrypointOrdinal++;
                continue;
            }
            if (hasPermission(plugin, INTERCEPT_PERMISSION)
                && overrides(entrypoint, "beforeSetParameterValue")
                && !subscribes(entrypoint, ParameterValueEvent.Before.class)) {
                installed.add(runtimeBroker.subscribeAdapter(
                    eventOwner,
                    ParameterValueEvent.Before.class,
                    entrypointOrdinal,
                    0,
                    event -> {
                        try {
                            event.setValue(hooks.beforeSetParameterValue(
                                event.parameter(),
                                event.value()
                            ));
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            throw hookFailure(sink, "beforeSetParameterValue", failure);
                        }
                    }
                ));
            }
            if (hasPermission(plugin, OBSERVE_PERMISSION)
                && overrides(entrypoint, "onParameterValueChanged")
                && !subscribes(entrypoint, ParameterValueEvent.On.class)) {
                installed.add(runtimeBroker.subscribeAdapter(
                    eventOwner,
                    ParameterValueEvent.On.class,
                    entrypointOrdinal,
                    1,
                    event -> {
                        try {
                            hooks.onParameterValueChanged(
                                event.parameter(), event.oldValue(), event.newValue()
                            );
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            throw hookFailure(sink, "onParameterValueChanged", failure);
                        }
                    }
                ));
            }
            if (hasPermission(plugin, OBSERVE_PERMISSION)
                && overrides(entrypoint, "afterSetParameterValue")
                && !subscribes(entrypoint, ParameterValueEvent.After.class)) {
                installed.add(runtimeBroker.subscribeAdapter(
                    eventOwner,
                    ParameterValueEvent.After.class,
                    entrypointOrdinal,
                    2,
                    event -> {
                        try {
                            hooks.afterSetParameterValue(event.parameter(), event.finalValue());
                        } catch (ThreadDeath | VirtualMachineError fatal) {
                            throw fatal;
                        } catch (Throwable failure) {
                            throw hookFailure(sink, "afterSetParameterValue", failure);
                        }
                    }
                ));
            }
            entrypointOrdinal++;
        }
        if (installed.isEmpty()) {
            return;
        }
        final AdapterRegistration registration = new AdapterRegistration(
            List.copyOf(installed)
        );
        synchronized (lifecycleLock) {
            if (registrations.putIfAbsent(eventOwner, registration) != null) {
                registration.close();
                throw new IllegalStateException(
                    "Parameter hook adapters already registered for " + eventOwner
                );
            }
            if (scope != null) {
                try {
                    scope.register(() -> unregisterGeneration(eventOwner, registration));
                } catch (RuntimeException | Error failure) {
                    unregisterGeneration(eventOwner, registration);
                    throw failure;
                }
            }
        }
    }

    public void unregister(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        synchronized (lifecycleLock) {
            coordinator.unregister(id);
            registrations.entrySet().removeIf(entry -> {
                if (!entry.getKey().pluginId().equals(id)) {
                    return false;
                }
                entry.getValue().close();
                return true;
            });
        }
    }

    public void unregister(final PluginEventOwnerKey owner) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        synchronized (lifecycleLock) {
            final AdapterRegistration registration = registrations.remove(key);
            if (registration != null) {
                registration.close();
            }
        }
    }

    private void unregisterGeneration(
        final PluginEventOwnerKey owner,
        final AdapterRegistration generation
    ) {
        synchronized (lifecycleLock) {
            if (registrations.remove(owner, generation)) {
                generation.close();
            }
        }
    }

    private static boolean overrides(final Object entrypoint, final String methodName) {
        try {
            final Class<?>[] parameterTypes = switch (methodName) {
                case "beforeSetParameterValue", "afterSetParameterValue" ->
                    new Class<?>[]{dev.turboism.sdk.cubism.model.Parameter.class, float.class};
                case "onParameterValueChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Parameter.class,
                        float.class,
                        float.class
                    };
                default -> throw new IllegalArgumentException(
                    "Unknown parameter hook method: " + methodName
                );
            };
            final java.lang.reflect.Method implementation = entrypoint.getClass()
                .getMethod(methodName, parameterTypes);
            return implementation.getDeclaringClass() != ParameterHooks.class
                && implementation.getDeclaringClass() != dev.turboism.sdk.cubism.CubismPlugin.class;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                "Parameter hook contract is unavailable: " + methodName,
                failure
            );
        }
    }

    private static boolean subscribes(
        final Object entrypoint,
        final Class<? extends dev.turboism.sdk.event.EventBus.TurboismEvent> eventType
    ) {
        return java.util.Arrays.stream(entrypoint.getClass().getMethods()).anyMatch(method ->
            method.isAnnotationPresent(dev.turboism.sdk.event.SubscribeEvent.class)
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isAssignableFrom(eventType)
        );
    }

    private static RuntimeException hookFailure(
        final PluginLogger logger,
        final String phase,
        final Throwable failure
    ) {
        try {
            logger.error("Cubism parameter lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Diagnostic failure must not replace the hook failure.
        }
        return failure instanceof RuntimeException runtimeFailure
            ? runtimeFailure
            : new IllegalStateException("Legacy parameter hook failed: " + phase, failure);
    }

    private static boolean hasPermission(
        final PluginDescriptor descriptor,
        final String permissionId
    ) {
        return descriptor.permissions().stream()
            .anyMatch(permission -> permission.id().equals(permissionId));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record AdapterRegistration(List<Registration> registrations) {
        private AdapterRegistration {
            registrations = List.copyOf(registrations);
        }

        private void close() {
            for (int index = registrations.size() - 1; index >= 0; index--) {
                registrations.get(index).close();
            }
        }
    }
}
