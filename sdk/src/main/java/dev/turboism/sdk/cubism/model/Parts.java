package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import java.util.List;
import java.util.NoSuchElementException;

/** Parts in one Cubism model. */
@PreviewApi
public interface Parts {

    List<Part> all();

    /**
     * Creates a Part with {@code name} and appends it under the model root.
     *
     * <p>The native two-phase flow is used: construct {@code CPartSource} → add to the Part source
     * set → attach to the Part tree, all admitted into one native Undo entry.</p>
     */
    default Part create(String name) { return create(name, null, -1); }

    /**
     * Creates a Part with {@code name} under {@code parent} at {@code index} (negative = append;
     * {@code null} parent = model root).
     *
     * @throws IllegalArgumentException when {@code name} is blank
     */
    default Part create(String name, Part parent, int index) {
        throw unavailable("Part creation");
    }

    /** Deletes {@code part} through the native selection + native DELETE command path. */
    default void remove(Part part) {
        throw unavailable("Part deletion");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }

    /** @throws NoSuchElementException when the ID is absent */
    Part find(PartId id);
}
