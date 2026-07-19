package dev.turboism.plugin.contextmenu.b1.domain;

import java.util.List;

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

    public ContextMenuLifecycleState state() {
        return state;
    }

    public List<ContextMenuContribution> inventory() {
        return INVENTORY;
    }

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

    public LifecycleOperationResult shutdown() {
        if (state == ContextMenuLifecycleState.SHUTDOWN) {
            return LifecycleOperationResult.UNCHANGED;
        }
        state = ContextMenuLifecycleState.SHUTDOWN;
        return LifecycleOperationResult.CHANGED;
    }
}
