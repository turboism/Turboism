package dev.turboism.plugin.contextmenu.b1.domain;

/**
 * The context-menu feature's lifecycle position.
 *
 * <p>{@link #DISABLED} and {@link #ENABLED} may alternate freely; {@link #SHUTDOWN} is terminal and
 * cannot be left.
 */
public enum ContextMenuLifecycleState {
    DISABLED,
    ENABLED,
    SHUTDOWN
}
