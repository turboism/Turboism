package dev.turboism.core.lifecycle;

/**
 * Where a plugin currently sits in the runtime lifecycle.
 *
 * <p>The first group is the normal progression from discovery through
 * {@code ENABLED} and back out via disable, shutdown, and unload. The second
 * group are terminal failure states, each naming the step that failed; a plugin
 * in one of them is not active.</p>
 */
public enum PluginLifecycleState {
    DISCOVERED,
    RESOLVED,
    CLASSLOADER_CREATED,
    CONSTRUCTED,
    LOADED,
    ENABLED,
    DISABLED,
    SHUTDOWN,
    UNLOADED,

    INVALID_DESCRIPTOR,
    DEPENDENCY_FAILED,
    PERMISSION_DENIED,
    CLASSLOADER_FAILED,
    LOAD_FAILED,
    ENABLE_FAILED,
    DISABLE_FAILED,
    SHUTDOWN_FAILED
}
