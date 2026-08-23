package dev.turboism.sdk.event;

import dev.turboism.sdk.PreviewApi;

/** Service-provider contract implemented by compile-time generated subscriber catalogs. */
@PreviewApi
public interface GeneratedSubscriberCatalog {

    /** @return the exact concrete entrypoint type this catalog binds */
    Class<?> entrypointType();

    /** Registers every generated subscriber method for the supplied exact entrypoint instance. */
    void register(Object entrypoint, EventSubscriberRegistrar registrar);
}
