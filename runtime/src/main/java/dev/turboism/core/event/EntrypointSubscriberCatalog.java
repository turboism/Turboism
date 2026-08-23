package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.SubscribeEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Validates and deterministically catalogs annotated plugin entrypoint methods. */
public final class EntrypointSubscriberCatalog {

    public List<EventSubscriberDescriptor> inspect(final List<?> entrypoints) {
        final List<?> values = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        final List<EventSubscriberDescriptor> descriptors = new ArrayList<>();
        for (int entrypointOrdinal = 0; entrypointOrdinal < values.size(); entrypointOrdinal++) {
            descriptors.addAll(inspectOne(
                Objects.requireNonNull(values.get(entrypointOrdinal), "entrypoint"),
                entrypointOrdinal
            ));
        }
        return List.copyOf(descriptors);
    }

    List<EventSubscriberDescriptor> inspectOne(
        final Object entrypoint,
        final int entrypointOrdinal
    ) {
        final Object value = Objects.requireNonNull(entrypoint, "entrypoint");
        validateDeclaredMethods(value.getClass());
        final List<Method> methods = Arrays.stream(value.getClass().getMethods())
            .filter(method -> method.isAnnotationPresent(SubscribeEvent.class))
            .filter(method -> !method.isBridge() && !method.isSynthetic())
            .sorted(Comparator.comparing(EntrypointSubscriberCatalog::signature))
            .toList();
        final List<EventSubscriberDescriptor> descriptors = new ArrayList<>();
        for (int methodOrdinal = 0; methodOrdinal < methods.size(); methodOrdinal++) {
            descriptors.add(descriptor(
                value,
                methods.get(methodOrdinal),
                entrypointOrdinal,
                methodOrdinal
            ));
        }
        return List.copyOf(descriptors);
    }

    private static void validateDeclaredMethods(final Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(SubscribeEvent.class)
                    || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers())) {
                    throw invalid(method, "subscriber must be a public instance method");
                }
            }
        }
    }

    private static EventSubscriberDescriptor descriptor(
        final Object entrypoint,
        final Method method,
        final int entrypointOrdinal,
        final int methodOrdinal
    ) {
        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
            throw invalid(method, "subscriber must be a public instance method");
        }
        if (method.getReturnType() != void.class) {
            throw invalid(method, "subscriber must return void");
        }
        if (method.getParameterCount() != 1) {
            throw invalid(method, "subscriber must declare exactly one event parameter");
        }
        final Class<?> parameterType = method.getParameterTypes()[0];
        if (!EventBus.TurboismEvent.class.isAssignableFrom(parameterType)) {
            throw invalid(method, "subscriber parameter must implement TurboismEvent");
        }
        @SuppressWarnings("unchecked")
        final Class<? extends EventBus.TurboismEvent> eventType =
            (Class<? extends EventBus.TurboismEvent>) parameterType;
        return new EventSubscriberDescriptor(
            entrypoint,
            method,
            eventType,
            method.getAnnotation(SubscribeEvent.class).priority(),
            entrypointOrdinal,
            methodOrdinal,
            signature(method)
        );
    }

    private static String signature(final Method method) {
        return method.getDeclaringClass().getName()
            + "#" + method.getName()
            + Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(java.util.stream.Collectors.joining(",", "(", ")"))
            + ":" + method.getReturnType().getName();
    }

    private static IllegalArgumentException invalid(
        final Method method,
        final String message
    ) {
        return new IllegalArgumentException(
            "Invalid @SubscribeEvent method " + signature(method) + ": " + message
        );
    }
}
