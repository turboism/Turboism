package dev.turboism.sdk.event;

import dev.turboism.sdk.Incubating;



/** Service-provider contract implemented by compile-time generated subscriber catalogs. */
@Incubating
public interface GeneratedSubscriberCatalog<T> {

    /** @return the exact concrete entrypoint type this catalog binds */
    Class<T> entrypointType();

    /** Registers every generated subscriber method for the supplied exact entrypoint instance. */
    void register(T entrypoint, EventSubscriberRegistrar registrar);
}
