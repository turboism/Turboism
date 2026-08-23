package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.EventSubscriberHandler;
import dev.turboism.sdk.failure.FailureBoundary;
import dev.turboism.sdk.failure.NoFailureInterception;
import dev.turboism.core.runtime.ContextClassLoaderScope;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** One validated subscriber and its deterministic ordering metadata. */
public record EventSubscriberDescriptor(
    Object entrypoint,
    Method method,
    Class<? extends EventBus.TurboismEvent> eventType,
    EventPriority priority,
    int entrypointOrdinal,
    int methodOrdinal,
    String canonicalSignature,
    EventSubscriberHandler<EventBus.TurboismEvent> handler
) {
    public EventSubscriberDescriptor(
        final Object entrypoint,
        final Method method,
        final Class<? extends EventBus.TurboismEvent> eventType,
        final EventPriority priority,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final String canonicalSignature
    ) {
        this(
            entrypoint,
            method,
            eventType,
            priority,
            entrypointOrdinal,
            methodOrdinal,
            canonicalSignature,
            reflective(entrypoint, method, canonicalSignature)
        );
    }

    public EventSubscriberDescriptor {
        entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
        eventType = Objects.requireNonNull(eventType, "eventType");
        priority = Objects.requireNonNull(priority, "priority");
        canonicalSignature = requireText(canonicalSignature, "canonicalSignature");
        handler = Objects.requireNonNull(handler, "handler");
        if (entrypointOrdinal < 0 || methodOrdinal < 0) {
            throw new IllegalArgumentException("subscriber ordinals must not be negative");
        }
    }

    static <T extends EventBus.TurboismEvent> EventSubscriberDescriptor generated(
        final Object entrypoint,
        final Class<T> eventType,
        final EventPriority priority,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final String canonicalSignature,
        final EventSubscriberHandler<T> handler
    ) {
        @SuppressWarnings("unchecked")
        final EventSubscriberHandler<EventBus.TurboismEvent> untyped = event ->
            handler.handle(eventType.cast(event));
        return new EventSubscriberDescriptor(
            entrypoint,
            generatedMethod(entrypoint, eventType, canonicalSignature),
            eventType,
            priority,
            entrypointOrdinal,
            methodOrdinal,
            canonicalSignature,
            untyped
        );
    }

    /** Returns the subscriber failure boundary declared on its method or entrypoint type. */
    public String failureBoundary() {
        final FailureBoundary methodBoundary = method.getAnnotation(FailureBoundary.class);
        if (methodBoundary != null) {
            return requireText(methodBoundary.value(), "@FailureBoundary value");
        }
        final FailureBoundary typeBoundary = entrypoint.getClass().getAnnotation(
            FailureBoundary.class
        );
        return typeBoundary == null
            ? "event.subscribe"
            : requireText(typeBoundary.value(), "@FailureBoundary value");
    }

    /** Returns whether centralized failure interception is disabled for this subscriber. */
    public boolean noFailureInterception() {
        return entrypoint.getClass().isAnnotationPresent(NoFailureInterception.class)
            || (method != null && method.isAnnotationPresent(NoFailureInterception.class));
    }

    private static <T extends EventBus.TurboismEvent> Method generatedMethod(
        final Object entrypoint,
        final Class<T> eventType,
        final String canonicalSignature
    ) {
        final Object target = Objects.requireNonNull(entrypoint, "entrypoint");
        try {
            return target.getClass().getMethod(
                methodName(canonicalSignature),
                Objects.requireNonNull(eventType, "eventType")
            );
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException(
                "Generated subscriber method is unavailable: " + canonicalSignature,
                failure
            );
        }
    }

    private static String methodName(final String canonicalSignature) {
        final String signature = requireText(canonicalSignature, "canonicalSignature");
        final int separator = signature.indexOf('#');
        final int parameters = signature.indexOf('(', separator + 1);
        if (separator < 0 || parameters <= separator + 1) {
            throw new IllegalArgumentException(
                "Generated subscriber canonicalSignature is invalid: " + signature
            );
        }
        return signature.substring(separator + 1, parameters);
    }

    /** Invokes the subscriber under its entrypoint ClassLoader context. */
    public void invoke(final EventBus.TurboismEvent event) throws Throwable {
        final ClassLoader classLoader = entrypoint.getClass().getClassLoader();
        if (classLoader == null) {
            handler.handle(event);
            return;
        }
        try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(classLoader)) {
            handler.handle(event);
        }
    }

    private static EventSubscriberHandler<EventBus.TurboismEvent> reflective(
        final Object entrypoint,
        final Method method,
        final String canonicalSignature
    ) {
        final Object target = Objects.requireNonNull(entrypoint, "entrypoint");
        final Method reflected = Objects.requireNonNull(method, "method");
        return event -> {
            try {
                reflected.invoke(target, event);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(
                    "Validated event subscriber became inaccessible: " + canonicalSignature,
                    failure
                );
            }
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
