package dev.turboism.plugin.psdimport.b1.application;

import dev.turboism.plugin.psdimport.b1.domain.LifecycleOperationResult;
import dev.turboism.plugin.psdimport.b1.domain.PsdActionDescriptor;
import dev.turboism.plugin.psdimport.b1.domain.PsdActionLifecycle;
import java.util.List;

public final class PsdActionApplication {
    private final PsdActionLifecycle lifecycle = new PsdActionLifecycle();
    public LifecycleOperationResult enable(){return lifecycle.enable();}
    public LifecycleOperationResult disable(){return lifecycle.disable();}
    public LifecycleOperationResult shutdown(){return lifecycle.shutdown();}
    public List<PsdActionDescriptor> inventory(){return lifecycle.inventory();}
}
