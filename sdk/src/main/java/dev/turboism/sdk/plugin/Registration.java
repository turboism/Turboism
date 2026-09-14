package dev.turboism.sdk.plugin;

/**
 * Reversible registration handle returned by all SDK registries.
 *
 * <p>A handle identifies its own registration; closing it must not affect
 * other registrations, including registrations of equal resources. Whether
 * repeated {@link #close()} calls are permitted is defined by the returning
 * registry.
 */
public interface Registration extends AutoCloseable {

    @Override
    void close();
}
