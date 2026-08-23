package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;
import java.util.NoSuchElementException;

/** Drawables in one Cubism model. */
public interface Drawables {

    List<Drawable> all();

    /** @throws NoSuchElementException when the ID is absent */
    Drawable find(ArtMeshId id);


    /**
     * Creates an ArtMesh with explicit geometry under {@code parent} at {@code index}
     * (negative = append; {@code null} parent = model root).
     */
    default Drawable create(
        String name,
        Part parent,
        int index,
        ArtMeshGeometry geometry
    ) {
        throw unavailable("ArtMesh creation");
    }

    /** Deletes {@code drawable} through the native selection + native DELETE command path. */
    default void remove(Drawable drawable) {
        throw unavailable("Drawable deletion");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
