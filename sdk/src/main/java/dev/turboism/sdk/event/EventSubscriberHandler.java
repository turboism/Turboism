package dev.turboism.sdk.event;

import dev.turboism.sdk.PreviewApi;

/** Direct generated invocation target for one annotated event subscriber. */
@PreviewApi
@FunctionalInterface
public interface EventSubscriberHandler<T extends EventBus.TurboismEvent> {

    /** Invokes the subscriber with its statically checked event type. */
    void handle(T event) throws Throwable;
}
