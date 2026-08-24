package dev.turboism.sdk.event;


/** Service-provider contract implemented by compile-time generated subscriber catalogs. */
public interface GeneratedSubscriberCatalog<T> {

    /** @return the exact concrete entrypoint type this catalog binds */
    Class<T> entrypointType();

    /** Registers every generated subscriber method for the supplied exact entrypoint instance. */
    void register(T entrypoint, EventSubscriberRegistrar registrar);
}
