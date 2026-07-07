package dev.turboism.sdk.plugin;

/**
 * Lifecycle contract for all Turboism plugins.
 */
public interface TurboismPlugin {

    default void init(PluginContext context) throws Exception {
    }

    default void enable() throws Exception {
    }

    default void disable() throws Exception {
    }

    default void shutdown() throws Exception {
    }
}
