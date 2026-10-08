package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;
import java.util.NoSuchElementException;

/** Drawables in one Cubism model. */
public interface Drawables {

    List<Drawable> all();

    /** Runs the native source-plane triangulator on detached input. Does not create
     * a drawable, bind parameters, change selection or add history. The result may
     * merge points or change faces; callers must compare it with their source before
     * using it to plan editable partitions. Availability is host-version specific. */
    default ArtMeshSourceTriangulation previewSourceTriangulation(ArtMeshGeometry geometry) {
        throw unavailable("Source mesh triangulation preview");
    }

    /** Replaces the Editor's transient ArtMesh selection. Does not edit model data or history. */
    default void select(List<ArtMeshId> ids) {
        throw unavailable("ArtMesh selection");
    }

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

    /**
     * Creates an ArtMesh with separate texture-source and default-form coordinates.
     * Source positions are used by the Editor to reconstruct UVs; default positions
     * are the initial visible shape. Both lists use the same vertex order and count.
     * The caller must supply source geometry consistent with the intended image mapping.
     * This operation does not register an image or change an existing mesh.
     * Creation, including both coordinate domains, is one native Undo operation.
     *
     * @throws UnsupportedOperationException when separate source coordinates are unavailable
     */
    default Drawable createWithSourceGeometry(
        String name,
        Part parent,
        int index,
        ArtMeshGeometry sourceGeometry,
        List<Point2> defaultPositions
    ) {
        throw unavailable("ArtMesh creation with separate source geometry");
    }

    /** Deletes {@code drawable} through the native selection + native DELETE command path. */
    default void remove(Drawable drawable) {
        throw unavailable("Drawable deletion");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
