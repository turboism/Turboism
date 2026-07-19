package dev.turboism.plugin.contextmenu.b1.application;

import dev.turboism.plugin.contextmenu.b1.domain.ContextMenuContribution;
import dev.turboism.plugin.contextmenu.b1.domain.ContextMenuLifecycle;
import dev.turboism.plugin.contextmenu.b1.domain.LifecycleOperationResult;
import java.util.List;

public final class ContextMenuApplication {
    private final ContextMenuLifecycle lifecycle = new ContextMenuLifecycle();

    public LifecycleOperationResult enable() { return lifecycle.enable(); }
    public LifecycleOperationResult disable() { return lifecycle.disable(); }
    public LifecycleOperationResult shutdown() { return lifecycle.shutdown(); }
    public List<ContextMenuContribution> inventory() { return lifecycle.inventory(); }
}
