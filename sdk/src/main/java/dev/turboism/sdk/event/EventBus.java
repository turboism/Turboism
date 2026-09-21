package dev.turboism.sdk.event;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/**
 * Typed event bus for plugin-to-plugin and framework-to-plugin communication.
 *
 * <p>Ordinary observations are asynchronous. Runtime-published events match by supertype: a
 * subscriber on a sealed family root receives every member state, and a subscriber on
 * {@link TurboismEvent} receives every runtime-owned event it is permitted to see.
 * Plugin-published events are routed differently — by exact type only — so a root
 * subscription never receives an arbitrary plugin-defined type. Delivery of each runtime-owned
 * concrete type checks its domain permissions, including
 * {@code turboism.cubism.model.intercept} for mutable {@code *Before} transform states.
 * Root subscriptions remain valid without every domain permission and receive only authorized
 * concrete types. Most events are immutable observations
 * delivered after the fact — only event contracts that document a callback scope (for
 * example {@code ParameterValueEvent.Before}) participate in synchronous
 * transformation. See {@code sdk/event-coverage.md} for the supported event
 * origins.</p>
 */
public interface EventBus {

    <T extends TurboismEvent> Registration subscribe(Class<T> type, Consumer<T> listener);

    <T extends TurboismEvent> void publish(T event);

    /**
     * Legacy nested event marker retained for backward compatibility. All shipped event
     * families implement the top-level {@link dev.turboism.sdk.event.TurboismEvent}
     * contract, which extends this marker so existing subscribers keep working.
     */
    interface TurboismEvent {
        // compatibility marker
    }
}
