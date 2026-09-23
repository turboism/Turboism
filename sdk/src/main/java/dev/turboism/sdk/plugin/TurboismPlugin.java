package dev.turboism.sdk.plugin;

/**
 * Lifecycle contract for all Turboism plugins.
 */
public interface TurboismPlugin {

    /**
     * Called once after the plugin is loaded, with its permission-scoped context.
     *
     * @param context the plugin's runtime view
     * @throws Exception when initialization fails; the plugin is not enabled afterwards
     */
    default void init(PluginContext context) throws Exception {
    }

    /** Called when the plugin transitions to the enabled state. */
    default void enable() throws Exception {
    }

    /** Called when the plugin transitions back to the disabled state. */
    default void disable() throws Exception {
    }

    /** Called when the plugin is being unloaded. */
    default void shutdown() throws Exception {
    }
}
