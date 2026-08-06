package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;
import java.util.List;
import java.util.NoSuchElementException;

/** Deformers in one Cubism model. */
@PreviewApi
public interface Deformers {

    List<Deformer> all();

    /** @throws NoSuchElementException when the ID is absent */
    Deformer find(DeformerId id);


    /**
     * Creates a Warp Deformer with {@code name} and {@code rows}×{@code columns} grid under
     * {@code parent} at {@code index} (negative = append; {@code null} parent = model root).
     *
     * <p>The native two-phase flow is used: construct {@code CWarpDeformerSource} → set grid
     * → add to the Deformer source set → attach to the Part tree, all admitted into one native
     * Undo entry.</p>
     *
     * @throws IllegalArgumentException when {@code name} is blank or rows/columns are not positive
     */
    default WarpDeformer createWarp(String name, Part parent, int index, int rows, int columns) {
        throw unavailable("Warp Deformer creation");
    }

    /**
     * Creates a Rotation Deformer with {@code name} under {@code parent} at {@code index}
     * (negative = append; {@code null} parent = model root).
     *
     * @throws IllegalArgumentException when {@code name} is blank
     */
    default RotationDeformer createRotation(String name, Part parent, int index) {
        throw unavailable("Rotation Deformer creation");
    }

    /** Deletes {@code deformer} through the native selection + native DELETE command path. */
    default void remove(Deformer deformer) {
        throw unavailable("Deformer deletion");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
