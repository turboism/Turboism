package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;

/** One Cubism Glue relation. */
public interface Glue {

    GlueId id();

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() { return id().value(); }

    /**
     * Renames this Glue (Inspector {@code name} entry) inside the native Undo
     * envelope. The Inspector requires a non-empty name.
     */
    default void setName(final String name) {
        throw unavailable("Glue name editing");
    }

    /**
     * Renames this Glue's Cubism ID (Inspector {@code id} entry) mirroring the
     * Inspector sequence (id rules, undo envelope, model instance refresh, verify,
     * palette refresh).
     */
    default void setId(final GlueId id) {
        throw unavailable("Glue ID editing");
    }

    /**
     * Returns the Glue intensity in model space {@code [0,1]} (Inspector shows
     * {@code 0..100%}).
     */
    default float intensity() { throw unavailable("Glue intensity"); }

    /**
     * Writes the Glue intensity (Inspector {@code intensity} entry) in model space
     * {@code [0,1]} inside the native Undo envelope.
     */
    default void setIntensity(final float intensity) {
        throw unavailable("Glue intensity editing");
    }

    /**
     * Rebinds the source ArtMesh (Inspector source target) inside the native Undo
     * envelope. The target must be an ArtMesh of the active model.
     */
    default void setDrawableA(final ArtMeshId id) {
        throw unavailable("Glue drawable-A editing");
    }

    /**
     * Rebinds the destination ArtMesh (Inspector destination target) inside the
     * native Undo envelope. The target must be an ArtMesh of the active model.
     */
    default void setDrawableB(final ArtMeshId id) {
        throw unavailable("Glue drawable-B editing");
    }

    default int index() { throw unavailable("Glue index"); }

    int drawableA();

    int drawableB();

    IntSequence parameters();

    default ArtMeshId drawableAId() { throw unavailable("Glue drawable A"); }

    default ArtMeshId drawableBId() { throw unavailable("Glue drawable B"); }

    default List<ParameterId> parameterIds() { throw unavailable("Glue parameters"); }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
