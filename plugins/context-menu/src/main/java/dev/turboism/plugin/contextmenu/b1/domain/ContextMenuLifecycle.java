package dev.turboism.plugin.contextmenu.b1.domain;

import java.util.List;

/**
 * Enable/disable state machine for the context-menu plugin, plus its fixed contribution inventory.
 *
 * <p>State starts at {@link ContextMenuLifecycleState#DISABLED}. Once shut down the instance is
 * spent: further enable and disable requests are refused rather than throwing. The inventory is a
 * compile-time constant and does not vary with state.
 *
 * <p>This class holds mutable state without synchronisation and is not thread-safe; confine an
 * instance to the thread that drives the plugin's lifecycle.
 */
public final class ContextMenuLifecycle {

    private static final List<ContextMenuContribution> INVENTORY = List.of(
        new ContextMenuContribution(
            "turboism.context-menu.parts.dispatch", ContextKind.PARTS, "context-menu.parts.label", 0
        ),
        new ContextMenuContribution(
            "turboism.context-menu.deformer.dispatch", ContextKind.DEFORMER, "context-menu.deformer.label", 1
        ),
        new ContextMenuContribution(
            "turboism.context-menu.parameter.dispatch", ContextKind.PARAMETER, "context-menu.parameter.label", 2
        ),
        new ContextMenuContribution(
            "turboism.context-menu.workspace-object.dispatch", ContextKind.WORKSPACE_OBJECT,
            "context-menu.workspace-object.label", 3
        )
    );

    private ContextMenuLifecycleState state = ContextMenuLifecycleState.DISABLED;

    /**
     * @return the current lifecycle position, {@link ContextMenuLifecycleState#DISABLED} until
     *         something transitions it
     */
    public ContextMenuLifecycleState state() {
        return state;
    }

    /**
     * The context-menu entries this plugin declares, one per {@link ContextKind}, in display order.
     *
     * @return the shared immutable inventory; identical for every instance and every lifecycle
     *         state, including after shutdown
     */
    public List<ContextMenuContribution> inventory() {
        return INVENTORY;
    }

    /**
     * Moves to {@link ContextMenuLifecycleState#ENABLED}.
     *
     * @return {@link LifecycleOperationResult#CHANGED} on an actual transition,
     *         {@link LifecycleOperationResult#UNCHANGED} when already enabled, or
     *         {@link LifecycleOperationResult#SHUTDOWN_REJECTED} after shutdown, in which case the
     *         state is left untouched
     */
    public LifecycleOperationResult enable() {
        if (state == ContextMenuLifecycleState.SHUTDOWN) {
            return LifecycleOperationResult.SHUTDOWN_REJECTED;
        }
        if (state == ContextMenuLifecycleState.ENABLED) {
            return LifecycleOperationResult.UNCHANGED;
        }
        state = ContextMenuLifecycleState.ENABLED;
        return LifecycleOperationResult.CHANGED;
    }

    /**
     * Moves to {@link ContextMenuLifecycleState#DISABLED}.
     *
     * @return {@link LifecycleOperationResult#CHANGED} on an actual transition,
     *         {@link LifecycleOperationResult#UNCHANGED} when already disabled, or
     *         {@link LifecycleOperationResult#SHUTDOWN_REJECTED} after shutdown, in which case the
     *         state is left untouched
     */
    public LifecycleOperationResult disable() {
        if (state == ContextMenuLifecycleState.SHUTDOWN) {
            return LifecycleOperationResult.SHUTDOWN_REJECTED;
        }
        if (state == ContextMenuLifecycleState.DISABLED) {
            return LifecycleOperationResult.UNCHANGED;
        }
        state = ContextMenuLifecycleState.DISABLED;
        return LifecycleOperationResult.CHANGED;
    }

    /**
     * Moves to the terminal {@link ContextMenuLifecycleState#SHUTDOWN} state from any other state.
     *
     * <p>Idempotent, and never rejected.
     *
     * @return {@link LifecycleOperationResult#CHANGED} the first time,
     *         {@link LifecycleOperationResult#UNCHANGED} on every later call
     */
    public LifecycleOperationResult shutdown() {
        if (state == ContextMenuLifecycleState.SHUTDOWN) {
            return LifecycleOperationResult.UNCHANGED;
        }
        state = ContextMenuLifecycleState.SHUTDOWN;
        return LifecycleOperationResult.CHANGED;
    }
}
