package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterGroupId;

import java.util.List;
import java.util.NoSuchElementException;

/** Parameter folders in one Editor model. */
public interface ParameterGroups {

    /** Returns all groups in stable pre-order, starting with the root group. */
    List<ParameterGroup> all();

    ParameterGroup root();

    /** @throws NoSuchElementException when the ID is absent */
    ParameterGroup find(ParameterGroupId id);

    /**
     * Creates a new parameter folder under the root folder and returns it.
     *
     * <p>The folder is created with the requested name and a fresh unique ID.
     * The write is undoable and generation-bound.</p>
     *
     * @throws IllegalArgumentException when the name is blank
     */
    default ParameterGroup addGroup(final String name) {
        java.util.Objects.requireNonNull(name, "name");
        throw new UnsupportedOperationException("ParameterGroup creation is unavailable.");
    }

    /**
     * Deletes one parameter folder and all of its descendant folders and parameters.
     *
     * @throws NoSuchElementException when the folder is absent
     */
    default void removeGroup(final dev.turboism.sdk.cubism.id.ParameterGroupId id) {
        java.util.Objects.requireNonNull(id, "id");
        throw new UnsupportedOperationException("ParameterGroup deletion is unavailable.");
    }

    /**
     * Moves one parameter into the requested folder through the Editor undo path.
     *
     * @throws NoSuchElementException when the parameter or the folder is absent
     */
    default void moveParameter(
        final dev.turboism.sdk.cubism.id.ParameterId parameterId,
        final dev.turboism.sdk.cubism.id.ParameterGroupId targetGroupId
    ) {
        java.util.Objects.requireNonNull(parameterId, "parameterId");
        java.util.Objects.requireNonNull(targetGroupId, "targetGroupId");
        throw new UnsupportedOperationException("ParameterGroup move is unavailable.");
    }
}
