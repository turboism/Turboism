package dev.turboism.sdk.event;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/**
 * Typed event bus for plugin-to-plugin and framework-to-plugin communication.
 */
public interface EventBus {

    <T extends TurboismEvent> Registration subscribe(Class<T> type, Consumer<T> listener);

    <T extends TurboismEvent> void publish(T event);

    interface TurboismEvent {
        // marker
    }
}
