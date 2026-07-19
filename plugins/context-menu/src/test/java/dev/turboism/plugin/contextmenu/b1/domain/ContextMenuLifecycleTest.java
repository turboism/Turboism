package dev.turboism.plugin.contextmenu.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ContextMenuLifecycleTest {

    @Test
    void exposesFrozenInventoryAndCompleteLifecycleMatrix() {
        final ContextMenuLifecycle lifecycle = new ContextMenuLifecycle();
        assertEquals(ContextMenuLifecycleState.DISABLED, lifecycle.state());
        assertEquals(List.of(
            "turboism.context-menu.parts.dispatch",
            "turboism.context-menu.deformer.dispatch",
            "turboism.context-menu.parameter.dispatch",
            "turboism.context-menu.workspace-object.dispatch"
        ), lifecycle.inventory().stream().map(ContextMenuContribution::id).toList());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.disable());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.disable());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.shutdown());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.shutdown());
        assertEquals(LifecycleOperationResult.SHUTDOWN_REJECTED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.SHUTDOWN_REJECTED, lifecycle.disable());
    }

    @Test
    void reenableRestoresExactImmutableInventory() {
        final ContextMenuLifecycle lifecycle = new ContextMenuLifecycle();
        final List<ContextMenuContribution> original = lifecycle.inventory();
        lifecycle.enable();
        lifecycle.disable();
        lifecycle.enable();
        assertEquals(original, lifecycle.inventory());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> lifecycle.inventory().add(original.get(0)));
    }
}
