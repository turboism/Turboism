package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;

import java.lang.reflect.Method;
import java.util.Objects;

/** One validated annotated subscriber and its deterministic ordering metadata. */
public record EventSubscriberDescriptor(
    Object entrypoint,
    Method method,
    Class<? extends EventBus.TurboismEvent> eventType,
    EventPriority priority,
    int entrypointOrdinal,
    int methodOrdinal,
    String canonicalSignature
) {
    public EventSubscriberDescriptor {
        entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
        method = Objects.requireNonNull(method, "method");
        eventType = Objects.requireNonNull(eventType, "eventType");
        priority = Objects.requireNonNull(priority, "priority");
        canonicalSignature = requireText(canonicalSignature, "canonicalSignature");
        if (entrypointOrdinal < 0 || methodOrdinal < 0) {
            throw new IllegalArgumentException("subscriber ordinals must not be negative");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
