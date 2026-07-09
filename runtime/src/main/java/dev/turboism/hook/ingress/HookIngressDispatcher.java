package dev.turboism.hook.ingress;

import dev.turboism.sdk.event.EventBus;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fake-first hook ingress dispatcher.
 *
 * <p>Dispatch validates that semantic ingress exists, remains production-disabled,
 * and only enqueues/publishes SDK-safe event DTOs. It does not execute plugin
 * logic inline and does not expose raw hook arguments.</p>
 */
public final class HookIngressDispatcher {

    private final HookIngressRegistry registry;
    private final Consumer<EventBus.TurboismEvent> eventSink;

    public HookIngressDispatcher(HookIngressRegistry registry, Consumer<EventBus.TurboismEvent> eventSink) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public HookIngressSpec dispatch(String hookId, String emittedEvent, EventBus.TurboismEvent event) {
        Objects.requireNonNull(event, "event");
        HookIngressSpec spec = registry.find(hookId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown hook ingress: " + hookId));
        if (spec.productionEnabled()) {
            throw new IllegalStateException("Production hook ingress is disabled in M12: " + hookId);
        }
        if (!spec.emittedEvent().equals(emittedEvent)) {
            throw new IllegalArgumentException(
                "Ingress " + hookId + " emits " + spec.emittedEvent() + ", not " + emittedEvent
            );
        }
        eventSink.accept(event);
        return spec;
    }
}
