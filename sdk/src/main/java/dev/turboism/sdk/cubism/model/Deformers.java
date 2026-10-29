package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import java.util.List;
import java.util.NoSuchElementException;

/** Deformers in one Cubism model. */
@CubismEditor
public interface Deformers {

    /** Returns every deformer in the model, in host order. */
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
     * Creates a Warp directly with its final grid and target deformer, in one creation edit.
     * The Part tree parent and target deformer are independent. Grid points are already in
     * the target deformer coordinate space (canvas space when the target is empty); this
     * method performs no coordinate conversion. No default grid is published first.
     *
     * @throws NoSuchElementException when the target deformer is absent from this model
     */
    default WarpDeformer createWarp(
            String name, Part parent, int index, java.util.Optional<DeformerId> target, WarpGrid grid) {
        throw unavailable("Initialized Warp Deformer creation");
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

    /**
     * Applies {@code deformer} to its child elements through the native command path on the
     * reviewed Editors. The host owns the child-element semantics.
     */
    @CubismEditor({
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    })
    default void applyToChildren(Deformer deformer) {
        throw unavailable("Apply deformer to child elements");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
