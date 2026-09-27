package dev.turboism.preview;

/**
 * Raised inside a lifecycle task when its generation lease was fenced after a deadline. The
 * generation must stop admitting work; anything already running unwinds through the normal
 * failure path.
 */
final class PluginLifecycleFenced extends RuntimeException {

    PluginLifecycleFenced(final String pluginId) {
        super("Plugin lifecycle generation was fenced after its deadline: " + pluginId);
    }
}
