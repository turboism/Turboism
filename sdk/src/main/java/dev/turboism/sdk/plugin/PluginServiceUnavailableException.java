package dev.turboism.sdk.plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by {@link PluginServiceDirectory#require(Class)} when the requested optional
 * service is not installed on the context.
 *
 * <p>The structured fields are the stable diagnostic contract. The exception message is
 * intended for humans and must not be parsed.
 */
public final class PluginServiceUnavailableException extends UnsupportedOperationException {

    private final Class<?> serviceType;
    private final PluginService service;

    /**
     * Creates a service-absence failure.
     *
     * @param serviceType the service interface that was required
     * @param service the catalog member naming the type, or {@code null} when no member maps it
     */
    public PluginServiceUnavailableException(final Class<?> serviceType, final PluginService service) {
        super(message(serviceType, service));
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        this.service = service;
    }

    /**
     * Returns the service interface that was required.
     *
     * @return the requested service type
     */
    public Class<?> serviceType() {
        return serviceType;
    }

    /**
     * Returns the catalog member naming the required type, or empty when none maps it.
     *
     * @return the matching {@link PluginService} member
     */
    public Optional<PluginService> service() {
        return Optional.ofNullable(service);
    }

    private static String message(final Class<?> serviceType, final PluginService service) {
        final String member = service == null ? "unlisted" : service.name();
        return "Required plugin service " + serviceType.getName() + " (" + member
                + ") is not installed on this context";
    }
}
