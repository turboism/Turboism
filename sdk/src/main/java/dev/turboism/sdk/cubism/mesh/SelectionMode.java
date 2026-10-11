package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * Membership operation applied to the exact active mesh selection.
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
@CubismEditor
public enum SelectionMode {
    /** Replace all current members with the supplied indices. */
    REPLACE,
    /** Add the supplied indices. */
    ADD,
    /** Remove the supplied indices. */
    REMOVE,
    /** Toggle membership of each supplied index. */
    TOGGLE
}
