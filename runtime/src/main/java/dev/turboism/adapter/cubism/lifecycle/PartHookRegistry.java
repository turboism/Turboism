package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.PartNameEvent;
import dev.turboism.sdk.event.cubism.PartOpacityEvent;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adapts legacy Part hook overrides onto the unified Runtime event broker. */
public final class PartHookRegistry {

    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;
    public static final String INTERCEPT_PERMISSION = ParameterHookRegistry.INTERCEPT_PERMISSION;

    private final PartLifecycleCoordinator coordinator;
    private final Object lifecycleLock = new Object();
    private final Map<String, AdapterRegistration> registrations = new HashMap<>();

    public PartHookRegistry(final PartLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public PartLifecycleCoordinator coordinator() {
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
        final List<PartHooks> hooks = Objects.requireNonNull(entrypoints, "entrypoints")
            .stream()
            .filter(PartHooks.class::isInstance)
            .map(PartHooks.class::cast)
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
            final PartLifecycleCoordinator.PluginHooks value =
                new PartLifecycleCoordinator.PluginHooks(
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
     * An entrypoint that also declares an annotated handler for a state is not adapted for that state.
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
            if (!(entrypoint instanceof PartHooks hooks)) {
                entrypointOrdinal++;
                continue;
            }
            if (hasPermission(plugin, INTERCEPT_PERMISSION)) {
                adaptBeforeOpacity(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
                adaptBeforeName(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
            }
            if (hasPermission(plugin, OBSERVE_PERMISSION)) {
                adaptOnOpacity(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
                adaptAfterOpacity(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
                adaptOnName(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
                adaptAfterName(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks, sink, installed
                );
            }
            entrypointOrdinal++;
        }
        if (installed.isEmpty()) {
            return;
        }
        final AdapterRegistration registration = new AdapterRegistration(installed);
        synchronized (lifecycleLock) {
            final AdapterRegistration previous = registrations.put(plugin.id(), registration);
            if (previous != null) {
                previous.close();
            }
            if (scope != null) {
                try {
                    scope.register(() -> unregisterGeneration(plugin.id(), registration));
                } catch (RuntimeException | Error failure) {
                    unregisterGeneration(plugin.id(), registration);
                    throw failure;
                }
            }
        }
    }

    private static void adaptBeforeOpacity(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "beforeSetPartOpacity")
            || subscribes(entrypoint, PartOpacityEvent.Before.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartOpacityEvent.Before.class, entrypointOrdinal, 0, event -> {
                try {
                    event.setOpacity(hooks.beforeSetPartOpacity(event.part(), event.opacity()));
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "beforeSetPartOpacity", failure);
                }
            }
        ));
    }

    private static void adaptOnOpacity(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "onPartOpacityChanged")
            || subscribes(entrypoint, PartOpacityEvent.On.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartOpacityEvent.On.class, entrypointOrdinal, 1, event -> {
                try {
                    hooks.onPartOpacityChanged(
                        event.part(), event.oldOpacity(), event.newOpacity()
                    );
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "onPartOpacityChanged", failure);
                }
            }
        ));
    }

    private static void adaptAfterOpacity(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "afterSetPartOpacity")
            || subscribes(entrypoint, PartOpacityEvent.After.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartOpacityEvent.After.class, entrypointOrdinal, 2, event -> {
                try {
                    hooks.afterSetPartOpacity(event.part(), event.finalOpacity());
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "afterSetPartOpacity", failure);
                }
            }
        ));
    }

    private static void adaptBeforeName(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "beforeSetPartName")
            || subscribes(entrypoint, PartNameEvent.Before.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartNameEvent.Before.class, entrypointOrdinal, 3, event -> {
                try {
                    event.setName(hooks.beforeSetPartName(event.part(), event.name()));
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "beforeSetPartName", failure);
                }
            }
        ));
    }

    private static void adaptOnName(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "onPartNameChanged")
            || subscribes(entrypoint, PartNameEvent.On.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartNameEvent.On.class, entrypointOrdinal, 4, event -> {
                try {
                    hooks.onPartNameChanged(event.part(), event.oldName(), event.newName());
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "onPartNameChanged", failure);
                }
            }
        ));
    }

    private static void adaptAfterName(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final PartHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, "afterSetPartName")
            || subscribes(entrypoint, PartNameEvent.After.class)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, PartNameEvent.After.class, entrypointOrdinal, 5, event -> {
                try {
                    hooks.afterSetPartName(event.part(), event.finalName());
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, "afterSetPartName", failure);
                }
            }
        ));
    }

    public void unregister(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        synchronized (lifecycleLock) {
            coordinator.unregister(id);
            final AdapterRegistration registration = registrations.remove(id);
            if (registration != null) {
                registration.close();
            }
        }
    }

    private void unregisterGeneration(
        final String pluginId,
        final AdapterRegistration generation
    ) {
        synchronized (lifecycleLock) {
            if (registrations.remove(pluginId, generation)) {
                generation.close();
            }
        }
    }

    private static boolean overrides(final Object entrypoint, final String methodName) {
        final Class<?>[] parameterTypes = switch (methodName) {
            case "beforeSetPartName", "afterSetPartName" ->
                new Class<?>[]{dev.turboism.sdk.cubism.model.Part.class, String.class};
            case "onPartNameChanged" ->
                new Class<?>[]{
                    dev.turboism.sdk.cubism.model.Part.class, String.class, String.class
                };
            case "beforeSetPartOpacity", "afterSetPartOpacity" ->
                new Class<?>[]{dev.turboism.sdk.cubism.model.Part.class, float.class};
            case "onPartOpacityChanged" ->
                new Class<?>[]{
                    dev.turboism.sdk.cubism.model.Part.class, float.class, float.class
                };
            default -> throw new IllegalArgumentException(
                "Unknown Part hook method: " + methodName
            );
        };
        try {
            final java.lang.reflect.Method implementation = entrypoint.getClass()
                .getMethod(methodName, parameterTypes);
            return implementation.getDeclaringClass() != PartHooks.class
                && implementation.getDeclaringClass() != dev.turboism.sdk.cubism.CubismPlugin.class;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                "Part hook contract is unavailable: " + methodName,
                failure
            );
        }
    }

    private static boolean subscribes(
        final Object entrypoint,
        final Class<? extends EventBus.TurboismEvent> eventType
    ) {
        return java.util.Arrays.stream(entrypoint.getClass().getMethods()).anyMatch(method ->
            method.isAnnotationPresent(SubscribeEvent.class)
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
            logger.error("Cubism Part lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Diagnostic failure must not replace the hook failure.
        }
        return failure instanceof RuntimeException runtimeFailure
            ? runtimeFailure
            : new IllegalStateException("Legacy Part hook failed: " + phase, failure);
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
