package dev.turboism.sdk.plugin;

/**
 * Reversible registration handle returned by all SDK registries.
 */
public interface Registration extends AutoCloseable {

    @Override
    void close();
}
