package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.Objects;
import java.util.Optional;

/**
 * One keyform Morph Target bound to an Editor object (ArtMesh, Part, or Deformer).
 *
 * <p>A Morph Target binds one object keyform to a parameter and a key value. The
 * binding is the authoring data that makes the object morph when the parameter
 * reaches the key value.</p>
 */
public interface MorphTarget {

    /** Returns the parameter that drives this Morph Target. */
    ParameterId parameterId();

    /**
     * Returns the key value at which this Morph Target becomes active.
     *
     * @throws IllegalStateException when the host no longer exposes the binding
     */
    float keyValue();

    /**
     * Returns the stable host keyform GUID when the backend exposes one.
     */
    default Optional<String> keyformGuid() {
        return Optional.empty();
    }

    /** Returns this Morph Target's position in the owner's stable list. */
    default int index() {
        throw unavailable("Morph Target index");
    }

    /**
     * Rebinds this Morph Target to another parameter while preserving the key value.
     *
     * @throws java.util.NoSuchElementException when the target parameter is absent
     */
    default void setParameter(final ParameterId id) {
        Objects.requireNonNull(id, "id");
        throw unavailable("Morph Target parameter editing");
    }

    /** Changes the key value at which this Morph Target becomes active. */
    default void setKeyValue(final float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("key value must be finite");
        throw unavailable("Morph Target key-value editing");
    }

    /** Atomically rebinds this Morph Target to a parameter and key value. */
    default void setParameterAndKeyValue(final ParameterId id, final float value) {
        Objects.requireNonNull(id, "id");
        if (!Float.isFinite(value)) throw new IllegalArgumentException("key value must be finite");
        throw unavailable("Morph Target editing");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
