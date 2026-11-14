package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared plugin-entrypoint queries for the legacy hook registries.
 *
 * <p>Every registry asks the same three questions about a plugin entrypoint: does the plugin hold
 * a permission, does the entrypoint subscribe to an event, and does it override a legacy callback
 * instead of inheriting the base no-op. The answers depend only on the entrypoint class, so each
 * one is resolved once per class and reused for every later plugin generation.</p>
 */
final class HookEntrypointSupport {

    /** Subscribed event parameter types per entrypoint class, in declaration order. */
    private static final ClassValue<List<Class<?>>> SUBSCRIBED_PARAMETERS = new ClassValue<>() {
        @Override
        protected List<Class<?>> computeValue(final Class<?> type) {
            final List<Class<?>> parameters = new ArrayList<>();
            for (final Method method : type.getMethods()) {
                if (method.isAnnotationPresent(SubscribeEvent.class) && method.getParameterCount() == 1) {
                    parameters.add(method.getParameterTypes()[0]);
                }
            }
            return List.copyOf(parameters);
        }
    };

    /** Override verdicts per entrypoint class, keyed by the exact callback signature. */
    private static final ClassValue<Map<OverrideKey, Boolean>> OVERRIDES = new ClassValue<>() {
        @Override
        protected Map<OverrideKey, Boolean> computeValue(final Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private HookEntrypointSupport() {}

    /**
     * Reports whether the plugin declares the permission.
     *
     * @param descriptor the plugin generation to inspect
     * @param permissionId the permission identifier the hook requires
     * @return {@code true} when the plugin holds that permission
     */
    static boolean hasPermission(final PluginDescriptor descriptor, final String permissionId) {
        return descriptor.permissions().stream()
                .anyMatch(permission -> permission.id().equals(permissionId));
    }

    /**
     * Reports whether the entrypoint declares a subscriber for the event type.
     *
     * @param entrypoint the plugin entrypoint instance
     * @param eventType the event the hook would deliver
     * @return {@code true} when one subscriber parameter accepts that event
     */
    static boolean subscribes(final Object entrypoint, final Class<? extends EventBus.TurboismEvent> eventType) {
        for (final Class<?> declared : SUBSCRIBED_PARAMETERS.get(entrypoint.getClass())) {
            if (declared.isAssignableFrom(eventType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether the entrypoint overrides a legacy callback instead of inheriting the base.
     *
     * @param entrypointType the plugin entrypoint class
     * @param methodName the legacy callback name
     * @param parameterTypes the exact callback parameter types
     * @param baseTypes declaring classes that represent the inherited no-op
     * @return {@code true} when a subclass supplies its own implementation
     * @throws NoSuchMethodException when the entrypoint class declares no such callback
     */
    static boolean overrides(
            final Class<?> entrypointType,
            final String methodName,
            final Class<?>[] parameterTypes,
            final Set<Class<?>> baseTypes)
            throws NoSuchMethodException {
        final OverrideKey key = new OverrideKey(methodName, List.of(parameterTypes), baseTypes);
        final Map<OverrideKey, Boolean> cache = OVERRIDES.get(entrypointType);
        final Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        final Method implementation = MethodHandleCache.method(entrypointType, methodName, parameterTypes);
        final boolean overridden = !baseTypes.contains(implementation.getDeclaringClass());
        cache.put(key, overridden);
        return overridden;
    }

    /** One legacy callback signature and the base declarations that count as "not overridden". */
    private record OverrideKey(String methodName, List<Class<?>> parameterTypes, Set<Class<?>> baseTypes) {}
}
