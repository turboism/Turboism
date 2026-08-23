package dev.turboism.plugin.contextmenu.b1.application;

import dev.turboism.plugin.contextmenu.b1.domain.ContextMenuContribution;
import dev.turboism.plugin.contextmenu.b1.domain.ContextMenuLifecycle;
import dev.turboism.plugin.contextmenu.b1.domain.LifecycleOperationResult;
import java.util.List;

/**
 * Application facade over the context-menu plugin's lifecycle state machine.
 *
 * <p>Owns one {@link ContextMenuLifecycle} for the life of the instance and adds no behaviour of
 * its own; every call delegates. Inherits the lifecycle's constraints: state starts disabled, a
 * shut-down instance refuses further transitions by returning a failed result rather than
 * throwing, and the instance is not thread-safe.
 */
public final class ContextMenuApplication {
    private final ContextMenuLifecycle lifecycle = new ContextMenuLifecycle();

    /**
     * @return the result of moving to the enabled state; unsuccessful when already enabled or
     *     already shut down
     */
    public LifecycleOperationResult enable() { return lifecycle.enable(); }
    /**
     * @return the result of moving to the disabled state; unsuccessful when already disabled or
     *     already shut down
     */
    public LifecycleOperationResult disable() { return lifecycle.disable(); }
    /**
     * Retires this instance permanently; no later enable or disable will succeed.
     *
     * @return the result of the shutdown transition
     */
    public LifecycleOperationResult shutdown() { return lifecycle.shutdown(); }
    /**
     * @return the plugin's fixed set of context-menu contributions; a compile-time constant that
     *     does not vary with lifecycle state
     */
    public List<ContextMenuContribution> inventory() { return lifecycle.inventory(); }
}
