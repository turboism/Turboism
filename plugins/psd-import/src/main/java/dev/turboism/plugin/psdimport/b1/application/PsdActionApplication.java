package dev.turboism.plugin.psdimport.b1.application;

import dev.turboism.plugin.psdimport.b1.domain.LifecycleOperationResult;
import dev.turboism.plugin.psdimport.b1.domain.PsdActionDescriptor;
import dev.turboism.plugin.psdimport.b1.domain.PsdActionLifecycle;
import java.util.List;

/**
 * Application-layer entry point for the PSD import plugin's action lifecycle.
 *
 * <p>Owns a single {@link PsdActionLifecycle} and forwards to it unchanged; it adds no state and no
 * validation of its own. Shutdown is terminal, so an instance is spent once shut down.
 *
 * <p>Not thread-safe: the underlying lifecycle holds unsynchronised mutable state.
 */
public final class PsdActionApplication {
    private final PsdActionLifecycle lifecycle = new PsdActionLifecycle();
    /**
     * Enables the PSD actions.
     *
     * @return the lifecycle's own verdict, including a refusal after shutdown
     */
    public LifecycleOperationResult enable(){return lifecycle.enable();}
    /**
     * Disables the PSD actions without ending the lifecycle.
     *
     * @return the lifecycle's own verdict, including a refusal after shutdown
     */
    public LifecycleOperationResult disable(){return lifecycle.disable();}
    /**
     * Ends the lifecycle permanently; later enable and disable calls are refused.
     *
     * @return {@code CHANGED} the first time, {@code UNCHANGED} afterwards
     */
    public LifecycleOperationResult shutdown(){return lifecycle.shutdown();}
    /**
     * @return the PSD actions this plugin declares; a fixed inventory that does not vary with
     *         lifecycle state
     */
    public List<PsdActionDescriptor> inventory(){return lifecycle.inventory();}
}
