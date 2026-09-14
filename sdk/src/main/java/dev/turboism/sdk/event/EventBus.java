package dev.turboism.sdk.event;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/**
 * Typed event bus for plugin-to-plugin and framework-to-plugin communication.
 */
public interface EventBus {

    /**
     * Subscribes {@code listener} to events assignable to {@code type}. Closing the returned
     * {@link Registration} unsubscribes the listener.
     */
    <T extends TurboismEvent> Registration subscribe(Class<T> type, Consumer<T> listener);

    /** Publishes {@code event} to every matching subscriber. */
    <T extends TurboismEvent> void publish(T event);

    /**
     * Legacy nested event marker retained while event implementations migrate
     * to the top-level {@link dev.turboism.sdk.event.TurboismEvent} contract.
     */
    interface TurboismEvent {
        // compatibility marker
    }
}
