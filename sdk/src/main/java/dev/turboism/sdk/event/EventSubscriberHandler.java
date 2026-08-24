package dev.turboism.sdk.event;


/** Direct generated invocation target for one annotated event subscriber. */
@FunctionalInterface
public interface EventSubscriberHandler<T extends EventBus.TurboismEvent> {

    /** Invokes the subscriber with its statically checked event type. */
    void handle(T event) throws Throwable;
}
