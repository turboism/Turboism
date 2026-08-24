package dev.turboism.sdk.event;


/**
 * Marker for a typed event that may be delivered through Turboism's plugin
 * event system.
 *
 * <p>Concrete event types express their own domain state. Related states may
 * share a sealed interface or abstract base, but Turboism imposes no global
 * before/on/after phase model.</p>
 */
public interface TurboismEvent extends EventBus.TurboismEvent {
}
