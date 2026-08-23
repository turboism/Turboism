package dev.turboism.hook.ingress;

import dev.turboism.sdk.event.EventBus;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Semantic hook ingress dispatcher.
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

    /**
     * Validates one hook firing against its registered spec and forwards the SDK event
     * to the sink.
     *
     * <p>Fails closed: an unregistered hook, a production-enabled spec, or an event name
     * the spec does not declare all throw before the sink is touched. Plugin logic is
     * never run inline here — the sink only enqueues.</p>
     *
     * @param hookId       registered ingress identity
     * @param emittedEvent the event name the caller claims to be emitting; must equal the
     *                     spec’s declared event
     * @param event        the SDK-safe event DTO to forward; carries no raw hook arguments
     * @return the spec that admitted this dispatch
     * @throws IllegalArgumentException when {@code hookId} is unknown, or
     *     {@code emittedEvent} disagrees with the spec
     * @throws IllegalStateException when the spec is production-enabled
     * @throws NullPointerException when {@code event} is {@code null}
     */
    public HookIngressSpec dispatch(String hookId, String emittedEvent, EventBus.TurboismEvent event) {
        Objects.requireNonNull(event, "event");
        HookIngressSpec spec = registry.find(hookId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown hook ingress: " + hookId));
        if (spec.productionEnabled()) {
            throw new IllegalStateException("Production hook ingress is disabled: " + hookId);
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
