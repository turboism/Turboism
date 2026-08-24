package dev.turboism.sdk.event;


/** Runtime-owned registration sink used by generated subscriber catalogs. */
public interface EventSubscriberRegistrar {

    /**
     * Registers one generated subscriber binding.
     *
     * @param eventType concrete or family event type accepted by the subscriber
     * @param priority deterministic dispatch priority
     * @param methodOrdinal canonical method order within the entrypoint
     * @param canonicalSignature stable declaring-class, method, parameter, and return signature
     * @param handler direct generated method binding
     */
    <T extends EventBus.TurboismEvent> void register(
        Class<T> eventType,
        EventPriority priority,
        int methodOrdinal,
        String canonicalSignature,
        EventSubscriberHandler<T> handler
    );
}
