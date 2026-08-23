package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;

/** Invokes one validated generated or reflective subscriber while preserving its failure. */
public final class EventSubscriberInvoker {

    public void invoke(
        final EventSubscriberDescriptor descriptor,
        final EventBus.TurboismEvent event
    ) throws Throwable {
        descriptor.invoke(event);
    }
}
