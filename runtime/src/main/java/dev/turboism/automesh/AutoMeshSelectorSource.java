package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.List;

/**
 * Everything the native Auto Mesh selector row reads: the session listing and its selection.
 *
 * <p>The row needs one thing the dispatch contract does not: the ordered algorithm listing, because
 * a combo box has to show every entry before the user picks one. This interface states exactly that
 * need, so the widget layer depends on a two-method view instead of the whole catalog and the
 * remaining wiring stays a one-line delegation.</p>
 */
public interface AutoMeshSelectorSource {

    /**
     * Lists the algorithms the row offers.
     *
     * @return the Editor's own generator first, then every live registration in registration order
     */
    List<AutoMeshAlgorithm> algorithms();

    /**
     * Returns the session selection the row reads and writes.
     *
     * @return the selection state of this Editor session
     */
    AutoMeshSelectionState selection();
}
