package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.cubism.model.Drawable;

/**
 * Selection-only view of the exact active native mesh editor.
 *
 * <p>Selection changes do not create an authoring transaction or Undo entry. This interface
 * intentionally exposes no geometry-authoring operations.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshEditor {

    /** Returns the active ArtMesh; brush projection uses the runtime-owned editable-mesh positions. */
    Drawable mesh();

    /** Returns the current canonical native vertex selection. */
    VertexSelection selection();

    /**
     * Replaces the native selection without authoring geometry or creating an Undo entry.
     *
     * @param selection canonical vertex indices
     */
    void select(VertexSelection selection);

    /**
     * Applies a membership operation without authoring geometry or creating an Undo entry.
     *
     * @param selection canonical vertex indices
     * @param mode membership operation
     */
    void select(VertexSelection selection, SelectionMode mode);
}
