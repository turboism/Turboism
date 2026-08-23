package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.EventSubscriberHandler;

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
            null,
            eventType,
            priority,
            entrypointOrdinal,
            methodOrdinal,
            canonicalSignature,
            untyped
        );
    }

    public void invoke(final EventBus.TurboismEvent event) throws Throwable {
        handler.handle(event);
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
