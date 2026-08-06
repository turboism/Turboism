package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import java.util.List;
import java.util.NoSuchElementException;

/** Parts in one Cubism model. */
@PreviewApi
public interface Parts {

    List<Part> all();

    /** @throws NoSuchElementException when the ID is absent */
    Part find(PartId id);

    /**
     * Creates a new Part under the root Part and returns it.
     *
     * <p>The Part is created with the requested ID as its display name and the
     * Editor default draw order. The write is undoable and generation-bound.</p>
     *
     * @throws IllegalArgumentException when the ID is blank or already present
     */
    default Part add(final String id) {
        return add(new PartId(id));
    }

    /**
     * Creates a new Part under the root Part and returns it.
     *
     * @throws IllegalArgumentException when the ID is blank or already present
     */
    default Part add(final PartId id) {
        java.util.Objects.requireNonNull(id, "id");
        throw unavailable("Part creation");
    }

    /**
     * Creates a new Part under the requested parent Part and returns it.
     *
     * @throws IllegalArgumentException when the ID is blank or already present
     * @throws NoSuchElementException when the parent Part is absent
     */
    default Part add(final String id, final PartId parentId) {
        return add(new PartId(id), parentId);
    }

    /**
     * Creates a new Part under the requested parent Part and returns it.
     *
     * @throws IllegalArgumentException when the ID is blank or already present
     * @throws NoSuchElementException when the parent Part is absent
     */
    default Part add(final PartId id, final PartId parentId) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(parentId, "parentId");
        throw unavailable("Part creation");
    }

    /**
     * Duplicates one Part (including its subtree) next to the source Part and returns the copy.
     *
     * @throws NoSuchElementException when the source Part is absent
     */
    default Part copy(final PartId id) {
        java.util.Objects.requireNonNull(id, "id");
        throw unavailable("Part duplication");
    }

    /**
     * Deletes one Part and its entire subtree.
     *
     * @throws NoSuchElementException when the Part is absent
     */
    default void remove(final PartId id) {
        java.util.Objects.requireNonNull(id, "id");
        throw unavailable("Part deletion");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
