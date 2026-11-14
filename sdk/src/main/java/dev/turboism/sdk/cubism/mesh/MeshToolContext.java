package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;

/**
 * Exact activation-bound services supplied to a custom mesh tool.
 *
 * <p>Every returned handle fails closed after its tool, plugin, host, session, or activation
 * identity is invalidated.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@CubismEditor(from = "5.0.00")
public interface MeshToolContext {
    /** Returns the selection-only editor handle for the exact activation. */
    MeshEditor editor();

    /** Returns the runtime-owned selection brush for the exact activation. */
    MeshBrush selectionBrush();

    /**
     * Returns the runtime-owned canvas point-and-edge authoring session for the exact
     * activation.
     *
     * <p>The session paints a plugin-computed layout on the canvas, follows the operator's anchor
     * placement and commits the layout as one Undo entry. It requires capability
     * {@code cubism.mesh.point-and-edge-layout} and permissions
     * {@code turboism.cubism.model.read} plus {@code turboism.cubism.model.write}; the runtime
     * checks the capability, the session checks the permissions per operation.</p>
     *
     * @return the activation-bound canvas session
     * @throws IllegalStateException when the registering plugin did not declare the capability or
     *     the activation has no exact native mesh session
     * @throws UnsupportedOperationException when the runtime provides no canvas session surface
     */
    default MeshCanvasSession canvasSession() {
        throw new UnsupportedOperationException("mesh canvas sessions are not available");
    }
}
