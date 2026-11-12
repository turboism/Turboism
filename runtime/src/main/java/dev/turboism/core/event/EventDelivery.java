package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;

/**
 * One queued event delivery: the concrete event and the route that must receive it.
 *
 * <p>The owner is re-checked at delivery time instead of trusting the enqueue snapshot, so a
 * mailbox that was reused by a newer generation can never deliver to the wrong plugin instance.
 */
record EventDelivery(EventBus.TurboismEvent event, EventSubscription<? extends EventBus.TurboismEvent> subscription) {

    void deliver(final PluginEventOwnerKey owner) {
        if (subscription.owner().equals(owner)) {
            subscription.deliverDispatched(event);
        }
    }
}
