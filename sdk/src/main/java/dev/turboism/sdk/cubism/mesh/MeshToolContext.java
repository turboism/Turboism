package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;

/**
 * Exact activation-bound services supplied to a custom mesh tool.
 *
 * <p>Every returned handle fails closed after its tool, plugin, host, session, or activation
 * identity is invalidated.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshToolContext {
    /** Returns the selection-only editor handle for the exact activation. */
    MeshEditor editor();

    /** Returns the runtime-owned selection brush for the exact activation. */
    MeshBrush selectionBrush();
}
