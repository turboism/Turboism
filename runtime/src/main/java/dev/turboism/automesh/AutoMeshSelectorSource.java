package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.List;
import java.util.function.Consumer;

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

    /**
     * Places the current Editor selection with the container's own live-update route.
     *
     * <p>The Editor re-places points when its own mode or parameter changes. The row asks for the
     * same route after it stores a plugin change, so selecting an algorithm or a preset behaves like
     * the Editor's own modes. A container that cannot answer the route reports the refusal and keeps
     * the stored selection, which the next native Generate still uses.</p>
     *
     * @param container the native Auto Mesh container the row decorates
     * @param diagnostics sink for a refused route; it must not throw
     */
    default void placeCurrentSelection(final Object container, final Consumer<String> diagnostics) {
        AutoMeshLivePlacement.place(container, diagnostics);
    }
}
